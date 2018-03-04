package com.wardellbagby.weightedshuffle

import com.wardellbagby.cachingsequence.CachingSequence
import kotlin.coroutines.experimental.buildSequence

/**
 * A weighted shuffle that tries its best to keep items that are grouped together by a key from appearing next to each
 * while still performing a sufficient shuffle on a given list of items.
 *
 * This isn't guaranteed behavior, though. There will be times when items that are grouped together with the same key
 * will appear next to each other, and there are times when an entirely random approach will perform a better distribution
 * of items.
 *
 * This shuffle is not fast. For small sizes of values (<1000), it can perform fast enough to be done on the UI thread.
 * However, [getShuffledIndexes] and [getShuffledItems] both return lazily evaluating [CachingSequence]s. If you only
 * need to know the values one-at-a-time (e.g., for a music shuffle app where you don't decide the next item until the
 * user skips), those two functions are recommended. If you need to know all of the values immediately, [getShuffledList]
 * will return a list of all of the shuffled items.
 *
 * @author Wardell Bagby
 */
object WeightedShuffle {

    /**
     * Data class representing an item for this shuffle, along with its weight.
     *
     * @property item the underlying item.
     * @property weight the weight of the item.
     */
    private data class WeightedValue<out ValueT>(val weight: Long, val item: ValueT, var skippedCount: Long = 0)

    /**
     * Generate indexes for the given list that when accessed sequentially as indexes in [values], will
     * provide a weighted shuffle.
     *
     * Since the items emitted by the sequence returned by this function are evaluated lazily, this is safe to call in
     * any context.
     *
     * @param values The values to shuffle.
     * @param drop How many items of the first n items in [values] to drop. These items won't be shuffled, but will
     *        still be included in the resulting sequence.
     * @param weightedBy A lambda that, given an item from [values], will return a key that will be used for grouping
     * similar items.
     *
     * @sample sampleGetShuffledIndexes
     */
    @JvmStatic
    fun <ItemT, KeyT> getShuffledIndexes(values: Iterable<ItemT>, drop: Int = 0, weightedBy: (ItemT) -> KeyT): CachingSequence<Int> {
        return shuffle(values, drop, weightedBy) {
            it.index
        }
    }

    /**
     * Generate a sequence for the given list that when accessed, will provide a weighted shuffled version of
     * [values].
     *
     * Since the items emitted by the sequence returned by this function are evaluated lazily, this is safe to call in
     * any context.
     *
     * @param values The values to shuffle.
     * @param drop How many items of the first n items in [values] to drop. These items won't be shuffled, but will
     *        still be included in the resulting sequence.
     * @param weightedBy A lambda that, given an item from [values], will return a key that will be used for grouping
     * similar items.
     *
     * @sample sampleGetShuffledItems
     */
    @JvmStatic
    fun <ItemT, KeyT> getShuffledItems(values: Iterable<ItemT>, drop: Int = 0, weightedBy: (ItemT) -> KeyT): CachingSequence<ItemT> {
        return shuffle(values, drop, weightedBy) {
            it.value
        }
    }

    /**
     * Creates a copy of [values] that has its items shuffled via a weighted shuffle.
     *
     * For a large [values], (size > 1000), this function can be a potentially lengthy operation. It is recommended that
     * this isn't called on any thread handling UI.
     *
     * @param values The values to shuffle.
     * @param drop How many items of the first n items in [values] to drop. These items won't be shuffled, but will
     *        still be included in the resulting sequence.
     * @param weightedBy A lambda that, given an item from [values], will return a key that will be used for grouping
     * similar items.
     *
     * @sample sampleGetShuffledList
     */
    @JvmStatic
    fun <ItemT, KeyT> getShuffledList(values: Iterable<ItemT>, drop: Int = 0, weightedBy: (ItemT) -> KeyT): List<ItemT> {
        return getShuffledItems(values, drop, weightedBy).toList()
    }

    private fun <ItemT, KeyT, YieldT> shuffle(values: Iterable<ItemT>, drop: Int, weightedBy: (ItemT) -> KeyT, yieldMapper: (IndexedValue<ItemT>) -> YieldT): CachingSequence<YieldT> {
        val valuesCopy = values.toList()

        if (valuesCopy.isEmpty()) throw IllegalArgumentException("values is empty.")
        if (valuesCopy.size <= drop) throw IllegalArgumentException("drop is greater than or equal to the size of values. size: ${valuesCopy.size}; drop: $drop")

        val totalCount = valuesCopy.count() - drop //Hold the total amount of queued items now.
        val droppedValues = valuesCopy.take(drop).mapIndexed { index, item -> IndexedValue(index, item) }.map(yieldMapper).asSequence()
        //We need to make sure the weights aren't going to be too low to get any kind of accuracy, so take the nearest higher power of 10 from totalCount.
        val totalWeight = Math.pow(10.0, Math.ceil(Math.log10(totalCount.toDouble()))).toLong()
        val keyMappedItems = convertToMap(valuesCopy.drop(drop), weightedBy)
        val weightedItems = convertToWeightedPairs(keyMappedItems, totalCount, totalWeight)
        return CachingSequence(droppedValues + generateShuffledSequence(weightedItems, totalWeight, yieldMapper))
    }

    private fun <ItemT, KeyT> convertToMap(values: List<ItemT>, usingKey: (ItemT) -> KeyT): Map<KeyT, MutableList<IndexedValue<ItemT>>> {
        //todo Can groupBy be used here instead?
        return values.foldIndexed(mutableMapOf()) { index, map, item ->
            /*Put every item into its own list based on its key (provided by usingKey), and store that into a map by key.
            We do this so that we can organize the items by how we want them weighted, and then choose from this map later when we're deciding
            which item to take.*/
            val key = usingKey(item)
            val items = map[key] ?: mutableListOf()
            map[key] = items.also { it.add(IndexedValue(index, item)) }
            map
        }
    }

    //todo It'd be nice if I could make these immutable...
    private fun <ItemT, KeyT> convertToWeightedPairs(map: Map<KeyT, MutableList<IndexedValue<ItemT>>>, totalCount: Int, totalWeight: Long): MutableList<WeightedValue<MutableList<IndexedValue<ItemT>>>> {
        /*A list were we assign weights to all of the items we made before in the map. The weights are determined by
          how many items we have in the list.*/
        val weightedList = mutableListOf<WeightedValue<MutableList<IndexedValue<ItemT>>>>()
        map.forEach {
            val weight = Math.ceil((it.value.size.toDouble() / totalCount) * totalWeight).toLong()
            weightedList.add(WeightedValue(weight, it.value))
        }
        //A good shuffle so that we don't always end up starting with the same few items.
        weightedList.shuffle()
        return weightedList
    }

    private fun <ItemT, YieldT> generateShuffledSequence(weightedList: MutableList<WeightedValue<MutableList<IndexedValue<ItemT>>>>, totalWeight: Long, yieldMapper: (IndexedValue<ItemT>) -> YieldT): Sequence<YieldT> {
        return buildSequence {
            var currentTotalWeight = totalWeight
            var shouldContinue: Boolean //Whether we have more items to add or if we can safely quit.
            var currentWeightedListIndex = -1 //Where we currently are in [weightedList]
            do {
                currentWeightedListIndex += 1
                if (currentWeightedListIndex >= weightedList.size) currentWeightedListIndex = 0 //Go from the beginning of weightedList if we're at the end.

                val currentWeightedValue = weightedList[currentWeightedListIndex]
                val currentItems = currentWeightedValue.item
                val currentWeight = currentWeightedValue.weight

                if (currentItems.isEmpty()) { //If our current items are empty, decide if we should continue or quit.
                    shouldContinue = weightedList.any { it.item.isNotEmpty() }
                    if (shouldContinue) {
                        currentTotalWeight -= currentWeight
                        continue
                    } else return@buildSequence
                }
                // Should we attempt to add a item from currentItems to our chosen indexes.
                val shouldAdd = shouldAddItem(currentWeightedValue, currentTotalWeight)

                if (shouldAdd) {
                    val chosenIndex = (Math.random() * currentItems.size).toInt() //We're gonna add a item! Choose a random one from currentItems
                    yield(yieldMapper(currentItems[chosenIndex]))
                    currentItems.removeAt(chosenIndex) //Remove it from our currentItems list so we don't choose it again.
                    weightedList[currentWeightedListIndex] = WeightedValue(currentWeight, currentItems) //Set the new currentItems (which is sans the item we added) back to the weightedList so we don't choose it again.
                }
                shouldContinue = !shouldAdd || currentItems.isNotEmpty() || weightedList.any { it.item.isNotEmpty() } //Are there any more items we can add?
            } while (shouldContinue)
        }
    }

    private fun <ItemT> shouldAddItem(item: WeightedValue<ItemT>, totalWeight: Long): Boolean {
        if (item.skippedCount >= totalWeight * .15) //A fallback for extreme cases.
            return true
        //Choose a random number between 0 and totalWeight and decide to add a item from currentItems based on the result.
        return (Math.random() * totalWeight <= item.weight).also {
            if (!it) item.skippedCount += 1
        }
    }

    /**
     * Perform a shuffle of every number from 0 to 1000, grouping by modulus 4.
     */
    private fun sampleGetShuffledIndexes() {
        val values = (0..1000).toList()
        val shuffledIndexes = WeightedShuffle.getShuffledIndexes(values) { it % 4 }
        shuffledIndexes.map { values[it] }.forEach(::print) //Use the indexes on the original list to print the new shuffled items.
        print(shuffledIndexes[0])
        print(shuffledIndexes[1] + shuffledIndexes[2])
    }

    /**
     * Perform a shuffle of every number from 0 to 1000, grouping by modulus 4.
     */
    private fun sampleGetShuffledItems() {
        val values = (0..1000)
        val shuffledItems = WeightedShuffle.getShuffledItems(values) { it % 4 }
        shuffledItems.forEach(::print) //Prints the new shuffled items.
    }

    /**
     * Perform a shuffle of every number from 0 to 1000, grouping by modulus 4.
     *
     * Performs this on a background thread in order to not block the UI.
     */
    private fun sampleGetShuffledList() {
        val values = (0..1000)
        object : Thread() {
            override fun run() {
                val shuffledItems = WeightedShuffle.getShuffledList(values) { it % 4 }
                shuffledItems.forEach(::print) //Prints the new shuffled items.
            }
        }.start()
    }
}