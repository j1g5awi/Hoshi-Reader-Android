cmake_minimum_required(VERSION 3.22.1)

project(hoshi_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(HOSHIDICTS_BRIDGE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/hoshidicts-kotlin-bridge")
set(HOSHIDICTS_ROOT "${HOSHIDICTS_BRIDGE_ROOT}/app/src/main/cpp/hoshidicts")

add_subdirectory(
    "${HOSHIDICTS_ROOT}"
    "${CMAKE_CURRENT_BINARY_DIR}/hoshidicts"
    EXCLUDE_FROM_ALL
)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(hoshidicts PRIVATE "$<$<COMPILE_LANGUAGE:CXX>:-Wno-c23-extensions>")
endif()

add_library(hoshidicts_jni SHARED
    "${HOSHIDICTS_BRIDGE_ROOT}/app/src/main/cpp/hoshidicts_jni.cpp"
)

target_include_directories(hoshidicts_jni PRIVATE
    "${HOSHIDICTS_ROOT}/include"
)

target_link_libraries(hoshidicts_jni PRIVATE
    hoshidicts
)

# ── SQLite amalgamation (static library for word_audio_jni) ──────
add_library(sqlite_amalgamation STATIC
    "${CMAKE_CURRENT_SOURCE_DIR}/sqlite/sqlite3.c"
)
target_include_directories(sqlite_amalgamation PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/sqlite"
)
target_compile_definitions(sqlite_amalgamation PUBLIC
    SQLITE_THREADSAFE=1
    SQLITE_ENABLE_DESERIALIZE=1
    SQLITE_OMIT_LOAD_EXTENSION=1
    SQLITE_OMIT_AUTHORIZATION=1
    SQLITE_OMIT_EXPLAIN=1
    SQLITE_OMIT_DEPRECATED=1
    SQLITE_OMIT_FOREIGN_KEY=1
    SQLITE_OMIT_BUILTIN_TEST=1
    SQLITE_OMIT_INCRBLOB=1
    SQLITE_OMIT_UPSERT=1
    SQLITE_OMIT_JSON=1
    SQLITE_OMIT_DATETIME_FUNCS=1
    SQLITE_OMIT_PROGRESS_CALLBACK=1
    SQLITE_TEMP_STORE=2
    SQLITE_DEFAULT_MEMSTATUS=0
)
target_compile_options(sqlite_amalgamation PRIVATE -Os)

# ── word_audio_jni ──────────────────────────────────────────────
add_library(word_audio_jni SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/audio/word_audio_jni.cpp"
)
target_include_directories(word_audio_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/sqlite"
)
find_library(log-lib log)
target_link_libraries(word_audio_jni PRIVATE
    sqlite_amalgamation
    ${log-lib}
)
target_compile_options(word_audio_jni PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(word_audio_jni PRIVATE -Wl,--gc-sections -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now)
