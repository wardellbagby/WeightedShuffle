# WeightedShuffle

A Kotlin library that provides a method of performing a weighted shuffle.

## Weighted Shuffle??

A weighted shuffle is a method of shuffling a sequence of items in a way such that items that belong to the same group will try somewhat to be further away from each other than a random shuffle would put them.

### So...they just try? Not even super hard?

Yeah, pretty much. Like really weak magnets.

### What's the reasoning?

This was originally made to shuffle an entire library of songs. Some people prefer listening to music by different artists if they can, even if the majority of the music is by the same artist. For me, it is an uncomfortable occurrence when I hear two songs from the same artist/album back to back when I have my entire library on shuffle. That's just how random works, though. A weighted shuffle can help to alleviate (but not completely eliminate) those occurrences in exchange for more overhead when shuffling items.

### How's the performance?

Not good. I wouldn't use it for anything super vital. Don't go running this on your production database and blaming me for your AWS bills.

### Why should I use this instead of library X?

There isn't another library that provides this functionality that I know of, but if there is, you should probably use that.

### When should I use this over a random shuffle?

 If all of the following are true, this shuffle is for you.

 1. You have a way of providing a grouping for all of your items.
 2. You would prefer if items from the same group tried to not show up next to each other after a shuffle.
 3. You either only ever need to know what the next item is in the shuffled sequence or it's okay for you to shuffle all of your items asynchronously.

## Getting Started

Simply check out this project and import it into Android Studio! Gradle and Android Studio should take care of the rest!

### As A Dependency

In your root `build.gradle`:

```
allprojects {
        repositories {
                ...
                maven { url 'https://jitpack.io' }
        }
}
```

In your app `build.gradle`:

```
dependencies {
        ...
        implementation 'com.github.wardellbagby:WeightedShuffle:0.1.0'
}
```

### Using

```kotlin
        //Get shuffled indexes from the provided list.
        val values = (0..1000).toList()
        //Group the items by applying modulus 4 to it.
        val shuffledIndexes = WeightedShuffle.getShuffledIndexes(values) { it % 4 }
        shuffledIndexes.map { values[it] }.forEach(::print) //Use the indexes on the original list to print the new shuffled items.
        print(shuffledIndexes[0])
        print(shuffledIndexes[1] + shuffledIndexes[2])

        //Get shuffled items from the provided list
        val values = (0..1000)
        val shuffledItems = WeightedShuffle.getShuffledItems(values) { it % 4 }
        shuffledItems.forEach(::print) //Prints the new shuffled items.
```

### Building

You can build this lib using:

```
./gradlew build
```

## Running the tests

Unit tests can be run with:

```
./gradlew check
```

### Code Style

This app uses [ktlint](https://ktlint.github.io/) for enforcing style constraints. Most ktlint errors can be fixed by running

```
./gradlew ktlintFormat
```

but not all. ktlint will output to console any errors it encounters.

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for the process for submitting pull requests to this project.

## License

This project is licensed under the LGPL-3.0 License - see the [LICENSE](LICENSE) file for details
