## Unjust
A command-line tool for detecting the "unjustified assumption in subclasses" in EO programs.
## Examples of programs with the defect
The examples of programs with the defect can be found in the [sandbox directory](/sandbox)

## Usage
1. Download the `.jar` file from the "Releases" section of this repository.
The tool expects more than 1 path to `.eo` files as arguments. 
2. Run the tool in the terminal like:
    ```shell
    java -jar unjust.jar file1.eo file2.eo
    ```
3. The results of the analysis should appear in the console.

## Compiling and building a "fat" `.jar`
1. Clone this repository and enter the directory.
2. To compile and run the application run:
    ```
   
    sbt "project unjust" "run arg1 arg2 arg3 [etc.]"
    ```
3. To build the distributable jar, run:
```shell
sbt "project unjust" assembly
```
