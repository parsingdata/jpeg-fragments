This is the source code accompanying the research paper [Problem solved: A reliable, deterministic method for JPEG fragmentation
point detection](https://doi.org/10.1016/j.fsidi.2023.301687) that won the Best Paper Award at the [Digital Forensics Research Conference Europe (DFRWS EU 2024)](https://dfrws.org/conferences/dfrws-eu-2024/). Please see the `CITATION.cff` file for full citation information.

In order to run the code, you need to have a Java 11 JDK installed along with a recent version of Maven.
Execute the following command to run the unit tests demonstrating the algorithm:

```bash
mvn clean verify
```

The full dataset consisted of over 230k files scraped from Wikipedia.
We provide a full list of names of all the files in the file `Wikipedia_dataset_230k_filenames_for_validating_jpeg_validator.txt`.

This implementation consists of over 4k test files, which is a curated subset of the full dataset constructed in order to represent all variants of different types of jpeg.
This includes baseline and progessive encodings along with features such as grayscale, spectral selection, chromatic subsampling, successive approximation, restart markers and even some specialized subsets.