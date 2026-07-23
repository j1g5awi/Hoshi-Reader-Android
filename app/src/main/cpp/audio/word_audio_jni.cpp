#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <string>
#include <vector>

#include "sqlite3.h"

struct SafDb {
    sqlite3 *db;
    void *map;
    size_t size;
    int fd;
};

static jlong nativeOpen(JNIEnv *env, jclass, jint fd, jlong size) {
    if (fd < 0 || size <= 0) return 0;
    void *map = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) {
        close(fd);
        return 0;
    }
    sqlite3 *db = nullptr;
    int rc = sqlite3_open_v2(":memory:", &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nullptr);
    if (rc != SQLITE_OK) {
        munmap(map, size);
        close(fd);
        return 0;
    }
    rc = sqlite3_deserialize(db, "main", static_cast<unsigned char*>(map),
                             size, size, SQLITE_DESERIALIZE_READONLY);
    if (rc != SQLITE_OK) {
        sqlite3_close(db);
        munmap(map, size);
        close(fd);
        return 0;
    }
    char *err = nullptr;
    sqlite3_exec(db, "PRAGMA query_only=1;PRAGMA journal_mode=OFF;"
                     "PRAGMA synchronous=OFF;PRAGMA temp_store=MEMORY;", nullptr, nullptr, &err);
    sqlite3_free(err);
    auto *handle = new SafDb{db, map, static_cast<size_t>(size), fd};
    return reinterpret_cast<jlong>(handle);
}

static void nativeClose(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<SafDb*>(ptr);
    if (!h) return;
    sqlite3_close(h->db);
    munmap(h->map, h->size);
    close(h->fd);
    delete h;
}

static jboolean nativeTestConnection(JNIEnv *env, jclass, jlong ptr) {
    auto *h = reinterpret_cast<SafDb*>(ptr);
    if (!h || !h->db) return JNI_FALSE;
    sqlite3_stmt *stmt = nullptr;
    int rc = sqlite3_prepare_v2(h->db, "SELECT count(*) FROM entries LIMIT 1", -1, &stmt, nullptr);
    if (rc != SQLITE_OK) return JNI_FALSE;
    jboolean ok = (sqlite3_step(stmt) == SQLITE_ROW) ? JNI_TRUE : JNI_FALSE;
    sqlite3_finalize(stmt);
    return ok;
}

static jobjectArray nativeFindEntries(JNIEnv *env, jclass, jlong ptr,
                                      jstring jterm, jstring jreading, jstring jsourceOrder) {
    auto *h = reinterpret_cast<SafDb*>(ptr);
    if (!h || !h->db) return nullptr;
    const char *term = env->GetStringUTFChars(jterm, nullptr);
    const char *reading = env->GetStringUTFChars(jreading, nullptr);
    const char *sourceOrder = env->GetStringUTFChars(jsourceOrder, nullptr);
    if (!term || !reading || !sourceOrder) {
        if (term) env->ReleaseStringUTFChars(jterm, term);
        if (reading) env->ReleaseStringUTFChars(jreading, reading);
        if (sourceOrder) env->ReleaseStringUTFChars(jsourceOrder, sourceOrder);
        return nullptr;
    }
    std::string sql = "SELECT file,source,speaker,display,reading,expression FROM entries WHERE ";
    bool hasReading = (strlen(reading) > 0);
    if (hasReading) {
        sql += "(expression=? OR reading=?) ORDER BY CASE WHEN reading=? THEN 0 ELSE 1 END,";
    } else {
        sql += "expression=? ORDER BY ";
    }
    sql += "CASE source ";
    std::string order(static_cast<const char*>(sourceOrder));
    size_t start = 0;
    int idx = 0;
    while (start < order.size()) {
        size_t end = order.find(',', start);
        std::string src = order.substr(start, end - start);
        sql += "WHEN '" + src + "' THEN " + std::to_string(idx++) + " ";
        start = (end == std::string::npos) ? order.size() : end + 1;
    }
    sql += "ELSE 999 END LIMIT 50";
    env->ReleaseStringUTFChars(jsourceOrder, sourceOrder);
    sqlite3_stmt *stmt = nullptr;
    int rc = sqlite3_prepare_v2(h->db, sql.c_str(), -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        env->ReleaseStringUTFChars(jterm, term);
        env->ReleaseStringUTFChars(jreading, reading);
        return nullptr;
    }
    sqlite3_bind_text(stmt, 1, term, -1, SQLITE_TRANSIENT);
    if (hasReading) {
        sqlite3_bind_text(stmt, 2, reading, -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 3, reading, -1, SQLITE_TRANSIENT);
    }
    env->ReleaseStringUTFChars(jterm, term);
    env->ReleaseStringUTFChars(jreading, reading);
    std::vector<std::vector<std::string>> rows;
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        rows.push_back({});
        for (int i = 0; i < 6; i++) {
            auto *txt = sqlite3_column_text(stmt, i);
            rows.back().push_back(txt ? reinterpret_cast<const char*>(txt) : "");
        }
    }
    sqlite3_finalize(stmt);
    if (rows.empty()) return nullptr;
    jclass entryClass = env->FindClass("moe/antimony/hoshi/features/audio/WordAudioDatabase$LocalEntry");
    if (!entryClass) return nullptr;
    jmethodID ctor = env->GetMethodID(entryClass, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (!ctor) return nullptr;
    jobjectArray result = env->NewObjectArray(rows.size(), entryClass, nullptr);
    for (size_t i = 0; i < rows.size(); i++) {
        auto &row = rows[i];
        jstring f = env->NewStringUTF(row[0].c_str());
        jstring s = env->NewStringUTF(row[1].c_str());
        jstring sp = row[2].empty() ? nullptr : env->NewStringUTF(row[2].c_str());
        jstring d = row[3].empty() ? nullptr : env->NewStringUTF(row[3].c_str());
        jstring r = env->NewStringUTF(row[4].c_str());
        jstring e = env->NewStringUTF(row[5].c_str());
        jobject obj = env->NewObject(entryClass, ctor, f, s, sp, d, r, e);
        env->SetObjectArrayElement(result, i, obj);
        env->DeleteLocalRef(f);
        env->DeleteLocalRef(s);
        if (sp) env->DeleteLocalRef(sp);
        if (d) env->DeleteLocalRef(d);
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(e);
        env->DeleteLocalRef(obj);
    }
    return result;
}

static jbyteArray nativeGetAudioData(JNIEnv *env, jclass, jlong ptr,
                                     jstring jfile, jstring jsource) {
    auto *h = reinterpret_cast<SafDb*>(ptr);
    if (!h || !h->db) return nullptr;
    const char *file = env->GetStringUTFChars(jfile, nullptr);
    const char *source = env->GetStringUTFChars(jsource, nullptr);
    if (!file || !source) {
        if (file) env->ReleaseStringUTFChars(jfile, file);
        if (source) env->ReleaseStringUTFChars(jsource, source);
        return nullptr;
    }
    sqlite3_stmt *stmt = nullptr;
    int rc = sqlite3_prepare_v2(h->db, "SELECT data FROM android WHERE file=? AND source=? LIMIT 1",
                                -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        env->ReleaseStringUTFChars(jfile, file);
        env->ReleaseStringUTFChars(jsource, source);
        return nullptr;
    }
    sqlite3_bind_text(stmt, 1, file, -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, source, -1, SQLITE_TRANSIENT);
    env->ReleaseStringUTFChars(jfile, file);
    env->ReleaseStringUTFChars(jsource, source);
    jbyteArray result = nullptr;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        auto *blob = sqlite3_column_blob(stmt, 0);
        int len = sqlite3_column_bytes(stmt, 0);
        if (blob && len > 0) {
            result = env->NewByteArray(len);
            env->SetByteArrayRegion(result, 0, len, static_cast<const jbyte*>(blob));
        }
    }
    sqlite3_finalize(stmt);
    return result;
}

static jobjectArray nativeGetSources(JNIEnv *env, jclass, jlong ptr) {
    auto *h = reinterpret_cast<SafDb*>(ptr);
    if (!h || !h->db) return nullptr;
    sqlite3_stmt *stmt = nullptr;
    const char *sql = "SELECT DISTINCT source FROM entries WHERE "
                      "lower(file) LIKE '%.mp3' OR lower(file) LIKE '%.opus' OR lower(file) LIKE '%.ogg'";
    if (sqlite3_prepare_v2(h->db, sql, -1, &stmt, nullptr) != SQLITE_OK) return nullptr;
    std::vector<std::string> sources;
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        auto *txt = sqlite3_column_text(stmt, 0);
        if (txt) sources.push_back(reinterpret_cast<const char*>(txt));
    }
    sqlite3_finalize(stmt);
    if (sources.empty()) return nullptr;
    jobjectArray result = env->NewObjectArray(sources.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < sources.size(); i++) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(sources[i].c_str()));
    }
    return result;
}

static const JNINativeMethod methods[] = {
    {"nativeOpen", "(IJ)J", reinterpret_cast<void*>(nativeOpen)},
    {"nativeClose", "(J)V", reinterpret_cast<void*>(nativeClose)},
    {"nativeTestConnection", "(J)Z", reinterpret_cast<void*>(nativeTestConnection)},
    {"nativeFindEntries", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)[Lmoe/antimony/hoshi/features/audio/WordAudioDatabase$LocalEntry;", reinterpret_cast<void*>(nativeFindEntries)},
    {"nativeGetAudioData", "(JLjava/lang/String;Ljava/lang/String;)[B", reinterpret_cast<void*>(nativeGetAudioData)},
    {"nativeGetSources", "(J)[Ljava/lang/String;", reinterpret_cast<void*>(nativeGetSources)},
};

extern "C" jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("moe/antimony/hoshi/features/audio/WordAudioDatabase");
    if (!cls) return JNI_ERR;
    if (env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
