package com.wardellbagby.weightedshuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * @author Wardell Bagby
 */
class WeightedShuffleTest {
    @Test
    fun testWeightedShuffle() {
        runShuffleTestWith(30, 2)
        runShuffleTestWith(50, 2)
        runShuffleTestWith(500, 3)

        runShuffleTestWith(1000, -5)
        runShuffleTestWith(1000, -10)
        runShuffleTestWith(1000, -33)
        runShuffleTestWith(1000, -89)
        runShuffleTestWith(1000, 89)
        runShuffleTestWith(1000, -60)
        runShuffleTestWith(1000, 60)

        runShuffleTestWith(2500, -20)
        runShuffleTestWith(2500, -21)
        runShuffleTestWith(2500, -16)
        runShuffleTestWith(2500, -13)

        runShuffleTestWith(5000, 18)
        runShuffleTestWith(5000, -18)

        runShuffleTestWith(10000, 100)
        runShuffleTestWith(10000, -100)
        runShuffleTestWith(10000, -500)
        runShuffleTestWith(10000, 500)

        runShuffleTestWith(10000, -80)
        runShuffleTestWith(10000, -20)
        runShuffleTestWith(10000, -200)
        runShuffleTestWith(10000, -50)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testEmptyValues() {
        WeightedShuffle.getShuffledIndexes((0 until 0)) { it % 2 }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDropHigherThanSize() {
        WeightedShuffle.getShuffledIndexes((0 until 100), 101) { it % 2 }
    }

    private data class GroupedItem<out GroupedT, out ValueT>(val group: GroupedT, val value: ValueT)

    /**
     * Creates a shuffled list with the provided size and groups every [andGroupEvery] amount
     * under one key. If [andGroupEvery] is negative, a random number between 1 and -[andGroupEvery]
     * will be chosen for the key. So if [andGroupEvery] is -100, a random number between 1 and 100 will
     * be chosen for the key of every item.
     */
    private fun runShuffleTestWith(size: Int, andGroupEvery: Int, runCount: Int = 100) {
        var weightedShuffleFailureCount = 0
        var randomShuffleFailureCount = 0
        //Run the test runCount times.
        for (i in 0 until runCount) {
            val list = (0 until size).map {
                GroupedItem(group = if (andGroupEvery < -1) {
                    (Math.random() * -andGroupEvery).toInt()
                } else
                    (it / andGroupEvery.toDouble()).toInt(), value = it)
            }.shuffled()

            val result = WeightedShuffle.getShuffledIndexes(list, 0) {
                it.group
            }.toList()

            assertEquals(size, result.size)
            assertEquals(result.distinct().size, result.size)

            for (j in 0 until result.size - 1 step 1) {
                val shuffledFirst = list[result[j]].group
                val shuffledSecond = list[result[j + 1]].group
                if (shuffledFirst == shuffledSecond) {
                    weightedShuffleFailureCount += 1
                }

                val randomFirst = list[j].group
                val randomSecond = list[j + 1].group
                if (randomFirst == randomSecond) {
                    randomShuffleFailureCount += 1
                }
            }
        }
        val maxPotentialFailures = runCount * (size - 1)
        when {
            weightedShuffleFailureCount > maxPotentialFailures * .1 -> fail("Failed more than 10% of the shuffles. Failure percentage: ${(weightedShuffleFailureCount.toDouble() / maxPotentialFailures) * 100}%")
            weightedShuffleFailureCount > (randomShuffleFailureCount * 1.5) -> fail("Failed more than the random shuffle failure percentage. Size: $size\nGroup By: $andGroupEvery\nWeighted Shuffle Failure Percentage: ${(weightedShuffleFailureCount.toDouble() / maxPotentialFailures) * 100}%\nRandom Shuffle Failure Percentage: ${(randomShuffleFailureCount.toDouble() / maxPotentialFailures) * 100}%")
            else -> println("""Size: $size
Grouped Every: $andGroupEvery
Max Potential Failures: $maxPotentialFailures
Actual Weighted Shuffle Failure count: $weightedShuffleFailureCount
Actual Random Shuffle Failure count: $randomShuffleFailureCount
Weighted Shuffle Failure percentage: ${(weightedShuffleFailureCount.toDouble() / maxPotentialFailures) * 100}%
Random Shuffle Failure percentage: ${(randomShuffleFailureCount.toDouble() / maxPotentialFailures) * 100}%
                    """)
        }
    }
}