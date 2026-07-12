package moe.antimony.hoshi.features.reader

internal enum class ReaderSwipeDirection {
    Left,
    Right,
}

internal fun readerNavigationDirectionForSwipe(
    isVerticalWriting: Boolean,
    swipeDirection: ReaderSwipeDirection,
    reverseDirection: Boolean = true,
): ReaderNavigationDirection = when (swipeDirection) {
    ReaderSwipeDirection.Left -> {
        if (isVerticalWriting && reverseDirection) {
            ReaderNavigationDirection.Backward
        } else {
            ReaderNavigationDirection.Forward
        }
    }
    ReaderSwipeDirection.Right -> {
        if (isVerticalWriting && reverseDirection) {
            ReaderNavigationDirection.Forward
        } else {
            ReaderNavigationDirection.Backward
        }
    }
}

internal enum class ReaderTapSide {
    Left,
    Right,
}

internal fun readerNavigationDirectionForTap(
    isVerticalWriting: Boolean,
    tapSide: ReaderTapSide,
): ReaderNavigationDirection = when (tapSide) {
    ReaderTapSide.Left -> if (isVerticalWriting) ReaderNavigationDirection.Forward else ReaderNavigationDirection.Backward
    ReaderTapSide.Right -> if (isVerticalWriting) ReaderNavigationDirection.Backward else ReaderNavigationDirection.Forward
}
