This is the source code to the jpeg fragmentation validation point paper soon to be published.

In order to run the code, you need to have a Java 11 JDK installed along with a recent version of Maven.
Execute the following command to run the unit tests demonstrating the algorithm:

```bash
mvn clean verify
```

The full dataset consisted of over 230k files scraped from Wikipedia.
We provide a full list of names of all the files in the file `Wikipedia_dataset_230k_filenames_for_validating_jpeg_validator.txt`.

This implementation consists of over 4k test files, which is a curated subset of the full dataset constructed in order to represent all variants of different types of jpeg.
This includes baseline and progessive along with features such as grayscale, spectral selection, chromatic subsampling, successive approximation, restart markers and even some specialized subsets. 