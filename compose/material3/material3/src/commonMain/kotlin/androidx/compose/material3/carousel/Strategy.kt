/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material3.carousel

import androidx.annotation.VisibleForTesting
import androidx.collection.FloatList
import androidx.collection.mutableFloatListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/**
 * Contains default values used across Strategies
 */
internal object StrategyDefaults {
    val MinSmallSize = 40.dp
    val MaxSmallSize = 56.dp
    val AnchorSize = 10.dp
    const val MediumLargeItemDiffThreshold = 0.85f
}

/**
 * A class responsible for supplying carousel with a [KeylineList] that is corrected for scroll
 * offset, layout direction, and snapping behaviors.
 *
 * Strategy is created using a [keylineList] block that returns a default [KeylineList]. This is
 * the list of keylines that define how items should be arranged, from left-to-right,
 * to achieve the carousel's desired appearance. For example, a start-aligned large
 * item, followed by a medium and a small item for a multi-browse carousel. Or a small item,
 * a center-aligned large item, and a small item for a centered hero carousel. Strategy will
 * use the [KeylineList] returned from the [keylineList] block to then derive new scroll, and layout
 * direction-aware [KeylineList]s and supply them for use by carousel. For example, when a
 * device is running in a right-to-left layout direction, Strategy will handle reversing the default
 * [KeylineList]. Or if the default keylines use a center-aligned large item, Strategy will shift
 * the large item to the start or end of the screen when the carousel is scrolled to the start or
 * end of the list, letting all items become large without having them detach from the edges of
 * the scroll container.
 *
 * @param keylineList a function that generates default keylines for this strategy based on the
 * carousel's available space. This function will be called anytime availableSpace changes.
 */
internal class Strategy(
    private val keylineList: (availableSpace: Float) -> KeylineList?
) {

    /** The keylines generated from the [keylineList] block. */
    internal lateinit var defaultKeylines: KeylineList
    /**
     * A list of [KeylineList]s that move the focal range from its position in [defaultKeylines]
     * to the start of the carousel container, one keyline at a time.
     */
    internal lateinit var startKeylineSteps: List<KeylineList>
    /**
     * A list of [KeylineList]s that move the focal range from its position in [defaultKeylines]
     * to the end of the carousel container, one keyline at a time.
     */
    internal lateinit var endKeylineSteps: List<KeylineList>
    /** The scroll distance needed to move through all steps in [startKeylineSteps]. */
    private var startShiftDistance: Float = 0f
    /** The scroll distance needed to move through all steps in [endKeylineSteps]. */
    private var endShiftDistance: Float = 0f
    /**
     * A list of floats whose index aligns with a [KeylineList] from [startKeylineSteps] and
     * whose value is the percentage of [startShiftDistance] that should be scrolled when the
     * start step is used.
     */
    private lateinit var startShiftPoints: FloatList
    /**
     * A list of floats whose index aligns with a [KeylineList] from [endKeylineSteps] and
     * whose value is the percentage of [endShiftDistance] that should be scrolled when the
     * end step is used.
     */
    private lateinit var endShiftPoints: FloatList

    /** The available space in the main axis used in the most recent call to [apply]. */
    private var availableSpace: Float = 0f
    /** The size of items when in focus and fully unmasked. */
    internal var itemMainAxisSize by mutableFloatStateOf(0f)

    /**
     * Whether this strategy holds a valid set of keylines that are ready for use.
     *
     * This is true after [apply] has been called and the [keylineList] block has returned a
     * non-null [KeylineList].
     */
    fun isValid() = itemMainAxisSize > 0f

    /**
     * Updates this [Strategy] based on carousel's main axis available space.
     *
     * This method must be called before a strategy can be used by carousel.
     *
     * @param availableSpace the size of the carousel container in scrolling axis
     */
    internal fun apply(availableSpace: Float): Strategy {
        // Skip computing new keylines and updating this strategy if
        // available space has not changed.
        if (this.availableSpace == availableSpace) {
            return this
        }

        val keylineList = keylineList.invoke(availableSpace) ?: return this
        val startKeylineSteps = getStartKeylineSteps(keylineList, availableSpace)
        val endKeylineSteps =
            getEndKeylineSteps(keylineList, availableSpace)

        // TODO: Update this to use the first/last focal keylines to calculate shift?
        val startShiftDistance = startKeylineSteps.last().first().unadjustedOffset -
            keylineList.first().unadjustedOffset
        val endShiftDistance = keylineList.last().unadjustedOffset -
            endKeylineSteps.last().last().unadjustedOffset

        this.defaultKeylines = keylineList
        this.defaultKeylines = keylineList
        this.startKeylineSteps = startKeylineSteps
        this.endKeylineSteps = endKeylineSteps
        this.startShiftDistance = startShiftDistance
        this.endShiftDistance = endShiftDistance
        this.startShiftPoints = getStepInterpolationPoints(
            startShiftDistance,
            startKeylineSteps,
            true
        )
        this.endShiftPoints = getStepInterpolationPoints(
            endShiftDistance,
            endKeylineSteps,
            false
        )
        this.availableSpace = availableSpace
        this.itemMainAxisSize = defaultKeylines.firstFocal.size

        return this
    }

    /**
     * Returns the [KeylineList] that should be used for the current [scrollOffset].
     *
     * @param scrollOffset the current scroll offset of the scrollable component
     * @param maxScrollOffset the maximum scroll offset
     * @param roundToNearestStep true if the KeylineList returned should be a complete shift step
     */
    internal fun getKeylineListForScrollOffset(
        scrollOffset: Float,
        maxScrollOffset: Float,
        roundToNearestStep: Boolean = false
    ): KeylineList {
        val startShiftOffset = startShiftDistance
        val endShiftOffset = maxScrollOffset - endShiftDistance

        // If we're not within either shift range, return the default keylines
        if (scrollOffset in startShiftOffset..endShiftOffset) {
            return defaultKeylines
        }

        var interpolation = lerp(
            outputMin = 1f,
            outputMax = 0f,
            inputMin = 0f,
            inputMax = startShiftOffset,
            value = scrollOffset
        )
        var shiftPoints = startShiftPoints
        var steps = startKeylineSteps

        if (scrollOffset > endShiftOffset) {
            interpolation = lerp(
                outputMin = 0f,
                outputMax = 1f,
                inputMin = endShiftOffset,
                inputMax = maxScrollOffset,
                value = scrollOffset
            )
            shiftPoints = endShiftPoints
            steps = endKeylineSteps
        }

        val shiftPointRange = getShiftPointRange(
            steps.size,
            shiftPoints,
            interpolation
        )

        if (roundToNearestStep) {
            val roundedStepIndex = if (shiftPointRange.steppedInterpolation.roundToInt() == 0) {
                shiftPointRange.fromStepIndex
            } else {
                shiftPointRange.toStepIndex
            }
            return steps[roundedStepIndex]
        }

        return lerp(
            steps[shiftPointRange.fromStepIndex],
            steps[shiftPointRange.toStepIndex],
            shiftPointRange.steppedInterpolation
        )
    }

    @VisibleForTesting
    internal fun getEndKeylines(): KeylineList {
        return endKeylineSteps.last()
    }

    @VisibleForTesting
    internal fun getStartKeylines(): KeylineList {
        return startKeylineSteps.last()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Strategy) return false
        // If neither strategy is valid, they should be considered equal
        if (!isValid() && !other.isValid()) return true

        if (isValid() != other.isValid()) return false
        if (availableSpace != other.availableSpace) return false
        if (itemMainAxisSize != other.itemMainAxisSize) return false
        if (startShiftDistance != other.startShiftDistance) return false
        if (endShiftDistance != other.endShiftDistance) return false
        if (startShiftPoints != other.startShiftPoints) return false
        if (endShiftPoints != other.endShiftPoints) return false
        // Only check default keyline equality since all other keylines are
        // derived from the defaults
        if (defaultKeylines != other.defaultKeylines) return false

        return true
    }

    override fun hashCode(): Int {
        if (!isValid()) return isValid().hashCode()

        var result = isValid().hashCode()
        result = 31 * result + availableSpace.hashCode()
        result = 31 * result + itemMainAxisSize.hashCode()
        result = 31 * result + startShiftDistance.hashCode()
        result = 31 * result + endShiftDistance.hashCode()
        result = 31 * result + startShiftPoints.hashCode()
        result = 31 * result + endShiftPoints.hashCode()
        result = 31 * result + defaultKeylines.hashCode()
        return result
    }

    companion object {

        /**
         * Generates discreet steps which move the focal range from its original position until
         * it reaches the start of the carousel container.
         *
         * Each step can only move the focal range by one keyline at a time to ensure every
         * item in the list passes through the focal range. Each step removes the keyline at the
         * start of the container and re-inserts it after the focal range in an order that retains
         * visual balance. This is repeated until the first focal keyline is at the start of the
         * container. Re-inserting keylines after the focal range in a balanced way is done by
         * looking at the size of they keyline next to the keyline that is being re-positioned
         * and finding a match on the other side of the focal range.
         *
         * The first state in the returned list is always the default [KeylineList] while
         * the last state will be the start state or the state that has the focal range at the
         * beginning of the carousel.
         */
        private fun getStartKeylineSteps(
            defaultKeylines: KeylineList,
            carouselMainAxisSize: Float
        ): List<KeylineList> {
            val steps: MutableList<KeylineList> = mutableListOf()
            steps.add(defaultKeylines)

            if (defaultKeylines.isFirstFocalItemAtStartOfContainer()) {
                return steps
            }

            val startIndex = defaultKeylines.firstNonAnchorIndex
            val endIndex = defaultKeylines.firstFocalIndex
            val numberOfSteps = endIndex - startIndex

            // If there are no steps but we need to account for a cutoff, create a
            // list of keylines shifted for the cutoff.
            if (numberOfSteps <= 0 && defaultKeylines.firstFocal.cutoff > 0) {
                steps.add(
                    moveKeylineAndCreateShiftedKeylineList(
                        from = defaultKeylines,
                        srcIndex = 0,
                        dstIndex = 0,
                        carouselMainAxisSize = carouselMainAxisSize
                    )
                )
                return steps
            }

            var i = 0
            while (i < numberOfSteps) {
                val prevStep = steps.last()
                val originalItemIndex = startIndex + i
                var dstIndex = defaultKeylines.lastIndex
                if (originalItemIndex > 0) {
                    val originalNeighborBeforeSize = defaultKeylines[originalItemIndex - 1].size
                    dstIndex = prevStep.firstIndexAfterFocalRangeWithSize(
                        originalNeighborBeforeSize
                    ) - 1
                }

                steps.add(
                    moveKeylineAndCreateShiftedKeylineList(
                        from = prevStep,
                        srcIndex = defaultKeylines.firstNonAnchorIndex,
                        dstIndex = dstIndex,
                        carouselMainAxisSize = carouselMainAxisSize
                    )
                )
                i++
            }

            return steps
        }

        /**
         * Generates discreet steps which move the focal range from its original position until
         * it reaches the end of the carousel container.
         *
         * Each step can only move the focal range by one keyline at a time to ensure every
         * item in the list passes through the focal range. Each step removes the keyline at the
         * end of the container and re-inserts it before the focal range in an order that retains
         * visual balance. This is repeated until the last focal keyline is at the start of the
         * container. Re-inserting keylines before the focal range in a balanced way is done by
         * looking at the size of they keyline next to the keyline that is being re-positioned
         * and finding a match on the other side of the focal range.
         *
         * The first state in the returned list is always the default [KeylineList] while
         * the last state will be the end state or the state that has the focal range at the
         * end of the carousel.
         */
        private fun getEndKeylineSteps(
            defaultKeylines: KeylineList,
            carouselMainAxisSize: Float
        ): List<KeylineList> {
            val steps: MutableList<KeylineList> = mutableListOf()
            steps.add(defaultKeylines)

            if (defaultKeylines.isLastFocalItemAtEndOfContainer(carouselMainAxisSize)) {
                return steps
            }

            val startIndex = defaultKeylines.lastFocalIndex
            val endIndex = defaultKeylines.lastNonAnchorIndex
            val numberOfSteps = endIndex - startIndex

            // If there are no steps but we need to account for a cutoff, create a
            // list of keylines shifted for the cutoff.
            if (numberOfSteps <= 0 && defaultKeylines.lastFocal.cutoff > 0) {
                steps.add(
                    moveKeylineAndCreateShiftedKeylineList(
                        from = defaultKeylines,
                        srcIndex = 0,
                        dstIndex = 0,
                        carouselMainAxisSize = carouselMainAxisSize
                    )
                )
                return steps
            }

            var i = 0
            while (i < numberOfSteps) {
                val prevStep = steps.last()
                val originalItemIndex = endIndex - i
                var dstIndex = 0

                if (originalItemIndex < defaultKeylines.lastIndex) {
                    val originalNeighborAfterSize = defaultKeylines[originalItemIndex + 1].size
                    dstIndex = prevStep.lastIndexBeforeFocalRangeWithSize(
                        originalNeighborAfterSize
                    ) + 1
                }

                val keylines = moveKeylineAndCreateShiftedKeylineList(
                    from = prevStep,
                    srcIndex = defaultKeylines.lastNonAnchorIndex,
                    dstIndex = dstIndex,
                    carouselMainAxisSize = carouselMainAxisSize
                )
                steps.add(keylines)
                i++
            }

            return steps
        }

        /**
         * Returns a new [KeylineList] where the keyline at [srcIndex] is moved to [dstIndex] and
         * with updated pivot and offsets that reflect any change in focal shift.
         */
        private fun moveKeylineAndCreateShiftedKeylineList(
            from: KeylineList,
            srcIndex: Int,
            dstIndex: Int,
            carouselMainAxisSize: Float
        ): KeylineList {
            // -1 if the pivot is shifting left/top, 1 if shifting right/bottom
            val pivotDir = if (srcIndex > dstIndex) 1 else -1
            val pivotDelta = (from[srcIndex].size - from[srcIndex].cutoff) * pivotDir
            val newPivotIndex = from.pivotIndex + pivotDir
            val newPivotOffset = from.pivot.offset + pivotDelta
            return keylineListOf(carouselMainAxisSize, newPivotIndex, newPivotOffset) {
                from.toMutableList()
                    .move(srcIndex, dstIndex)
                    .fastForEach { k -> add(k.size, k.isAnchor) }
            }
        }

        /**
         * Creates and returns a list of float values containing points between 0 and 1 that
         * represent interpolation values for when the [KeylineList] at the corresponding index in
         * [steps] should be visible.
         *
         * For example, if [steps] has a size of 4, this method will return an array of 4 float
         * values that could look like [0, .33, .66, 1]. When interpolating through a list of
         * [KeylineList]s, an interpolation value will be between 0-1. This interpolation will be
         * used to find the range it falls within from this method's returned value. If
         * interpolation is .25, that would fall between the 0 and .33, the 0th and 1st indices
         * of the float array. Meaning the 0th and 1st items from [steps] should be the current
         * [KeylineList]s being interpolated. This is an example with equally distributed values
         * but these values will typically be unequally distributed since their size depends on
         * the distance keylines shift between each step.
         *
         * @see [lerp] for more details on how interpolation points are used
         * @see [getKeylineListForScrollOffset] for more details on how interpolation points
         * are used
         *
         * @param totalShiftDistance the total distance keylines will shift between the first and
         * last [KeylineList] of [steps]
         * @param steps the steps to find interpolation points for
         * @param isShiftingLeft true if this method should find interpolation points for shifting
         * keylines to the left/top of a carousel, false if this method should find interpolation
         * points for shifting keylines to the right/bottom of a carousel
         * @return a list of floats, equal in size to [steps] that contains points between 0-1
         * that align with when a [KeylineList] from [steps should be shown for a 0-1
         * interpolation value
         */
        private fun getStepInterpolationPoints(
            totalShiftDistance: Float,
            steps: List<KeylineList>,
            isShiftingLeft: Boolean
        ): FloatList {
            val points = mutableFloatListOf(0f)
            if (totalShiftDistance == 0f) {
                return points
            }

            (1 until steps.size).map { i ->
                val prevKeylines = steps[i - 1]
                val currKeylines = steps[i]
                val distanceShifted = if (isShiftingLeft) {
                    currKeylines.first().unadjustedOffset - prevKeylines.first().unadjustedOffset
                } else {
                    prevKeylines.last().unadjustedOffset - currKeylines.last().unadjustedOffset
                }
                val stepPercentage = distanceShifted / totalShiftDistance
                val point = if (i == steps.lastIndex) 1f else points[i - 1] + stepPercentage
                points.add(point)
            }
            return points
        }

        private data class ShiftPointRange(
            val fromStepIndex: Int,
            val toStepIndex: Int,
            val steppedInterpolation: Float
        )

        private fun getShiftPointRange(
            stepsCount: Int,
            shiftPoint: FloatList,
            interpolation: Float
        ): ShiftPointRange {
            var lowerBounds = shiftPoint[0]
            (1 until stepsCount).forEach { i ->
                val upperBounds = shiftPoint[i]
                if (interpolation <= upperBounds) {
                    return ShiftPointRange(
                        fromStepIndex = i - 1,
                        toStepIndex = i,
                        steppedInterpolation = lerp(0f, 1f, lowerBounds, upperBounds, interpolation)
                    )
                }
                lowerBounds = upperBounds
            }
            return ShiftPointRange(
                fromStepIndex = 0,
                toStepIndex = 0,
                steppedInterpolation = 0f
            )
        }

        private fun MutableList<Keyline>.move(srcIndex: Int, dstIndex: Int): MutableList<Keyline> {
            val keyline = get(srcIndex)
            removeAt(srcIndex)
            add(dstIndex, keyline)
            return this
        }
    }
}

private fun lerp(
    outputMin: Float,
    outputMax: Float,
    inputMin: Float,
    inputMax: Float,
    value: Float
): Float {
    if (value <= inputMin) {
        return outputMin
    } else if (value >= inputMax) {
        return outputMax
    }

    return lerp(outputMin, outputMax, (value - inputMin) / (inputMax - inputMin))
}
