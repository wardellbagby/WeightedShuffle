package com.wardellbagby.weightedshuffle

import com.wardellbagby.cachingsequence.CachingSequence
import com.yundom.kache.Builder
import com.yundom.kache.Kache
import com.yundom.kache.config.FIFO
import java.util.*
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
     * @property value the underlying item.
     * @property key the key for this item.
     */
    private data class KeyValue<out ValueT, out KeyT>(val key: KeyT, val value: ValueT, var skippedCount: Long = 0)

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

        val droppedValues = valuesCopy.take(drop).mapIndexed { index, item -> IndexedValue(index, item) }.map(yieldMapper).asSequence()

        val keyMappedItems = convertToMap(valuesCopy.drop(drop), weightedBy)
        equalizeMap(keyMappedItems)
        val groupedItems = convertToKeyValueList(keyMappedItems)
        return CachingSequence(droppedValues + generateShuffledSequence(groupedItems, yieldMapper))
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

    private fun <ItemT, KeyT> equalizeMap(unequalizedMap: Map<KeyT, MutableList<IndexedValue<ItemT>>>) {
        val maxItemCount = unequalizedMap.maxBy { it.value.size }?.value?.size
                ?: throw IllegalStateException("For some reason, the weighted list doesn't have a max....? This is a huge bug.")
        unequalizedMap.forEach {
            val size = it.value.size
            val firstItem = it.value.first().value
            if (size < maxItemCount) it.value.addAll((size until maxItemCount).map { IndexedValue(-1, firstItem) })
        }

    }

    //todo It'd be nice if I could make these immutable...
    private fun <ItemT, KeyT> convertToKeyValueList(map: Map<KeyT, MutableList<IndexedValue<ItemT>>>): MutableList<KeyValue<MutableList<IndexedValue<ItemT>>, KeyT>> {
        /*A list were we assign weights to all of the items we made before in the map. The weights are determined by
          how many items we have in the list.*/
        val keyValueList = mutableListOf<KeyValue<MutableList<IndexedValue<ItemT>>, KeyT>>()
        map.forEach {
            keyValueList.add(KeyValue(it.key, it.value))
        }
        //A good shuffle so that we don't always end up starting with the same few items.
        keyValueList.shuffle()
        return keyValueList
    }

    private fun <ItemT, YieldT, KeyT> generateShuffledSequence(groupedItems: MutableList<KeyValue<MutableList<IndexedValue<ItemT>>, KeyT>>, yieldMapper: (IndexedValue<ItemT>) -> YieldT): Sequence<YieldT> {
        return buildSequence {
            var shouldContinue: Boolean //Whether we have more items to add or if we can safely quit.
            var currentGroupedItemsIndex: Int //Where we currently are in [groupedItems]
            val cacheCapacity = Math.max((groupedItems.size * .75).toInt(), 1)
            val randomInstance = Random()

            val recentlySelectedItems = Builder.build<KeyT, Any> {
                policy = FIFO
                capacity = cacheCapacity
            }

            do {
                currentGroupedItemsIndex = randomInstance.nextInt(groupedItems.size)

                val currentWeightedKeyValue = groupedItems[currentGroupedItemsIndex]
                val currentItems = currentWeightedKeyValue.value

                // Should we attempt to add a item from currentItems to our chosen indexes.
                val shouldAdd = shouldAddItem(currentWeightedKeyValue, recentlySelectedItems)

                if (shouldAdd) {
                    val chosenIndex = (Math.random() * currentItems.size).toInt() //We're gonna add a item! Choose a random one from currentItems
                    val chosenItem = currentItems[chosenIndex]
                    val currentKey = currentWeightedKeyValue.key
                    currentItems.removeAt(chosenIndex) //Remove it from our currentItems list so we don't choose it again.
                    if (currentItems.isEmpty()) {
                        groupedItems.removeAt(currentGroupedItemsIndex) // No longer look for this list
                    } else {
                        groupedItems[currentGroupedItemsIndex] = KeyValue(currentKey, currentItems) //Set the new currentItems (which is sans the item we added) back to the groupedItems so we don't choose it again.
                    }
                    if (chosenItem.index >= 0) {
                        recentlySelectedItems.put(currentKey, 0)
                        yield(yieldMapper(chosenItem))
                    }
                }
                shouldContinue = !shouldAdd || currentItems.isNotEmpty() || groupedItems.any { it.value.isNotEmpty() } //Are there any more items we can add?
            } while (shouldContinue)
        }
    }

    private fun <KeyT> shouldAddItem(item: KeyValue<*, KeyT>, cache: Kache<KeyT, Any>): Boolean {
        return when {
            cache.get(item.key) == null -> true
            cache.get(item.key) != null && item.skippedCount > cache.getMaxSize() -> {
                cache.remove(item.key)
                true
            }
            else -> {
                item.skippedCount += 1
                false
            }
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