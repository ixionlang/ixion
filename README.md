<div align="center">
  <img src="https://github.com/ixionlang/ixion/blob/main/assets/logo.png" width="500">

Multi-paradigm compiled programming language for the jvm platform.
</div>


Specification: [Русский](spec/specification.ru.md) ·
[English](spec/specification.en.md)

Docs: [Русский](docs/ru.md)


## Build from source

Install JDK 21 and make sure `java -version` reports version 21. The repository includes the Gradle Wrapper, which downloads Gradle and the project dependencies on the first build.

Clone the repository and open its root directory.

```sh
git clone https://github.com/ixionlang/ixion.git
cd ixion
```

On macOS or Linux, run:

```sh
./gradlew build
```

On Windows, run this in PowerShell:

```powershell
.\gradlew.bat build
```

The build runs the tests and writes the compiler JAR to `build/libs/`.

tree: 
```scala
use <std>

type Tree[T] = enum {
  Leaf(value: T),
  Node(left: Tree[T], right: Tree[T]),
}

def mapTree[A, B](tree: Tree[A], f: (A) => B): Tree[B] {
  return case tree {
    Tree::Leaf(value) => Tree::Leaf(f(value)),
    Tree::Node(left, right) => Tree::Node(mapTree(left, f), mapTree(right, f)),
  }
}

def main() {
  let tree = Tree::Node(Tree::Leaf(-1), Tree::Leaf(7))
  std::println(mapTree(tree, lambda (n) { return n > 0 }))
}
```


Result:

```scala
use <std>

def mapResult[A, B, E](result: Result[A, E], f: (A) => B): Result[B, E] {
    return case result {
        Result::Ok(value) => Result::Ok(f(value)),
        Result::Err(error) => Result::Err(error),
    }
}

def main() {
    let success: Result[int, string] = Result::Ok(21)
    let failure: Result[int, string] = Result::Err("offline")

    std::println(mapResult(success, lambda (n) { return n >= 18 }))
    // Ok(true)

    std::println(mapResult(failure, lambda (n) { return n >= 18 }))
    // Err(offline)
}

```


## Contributions
We will review and help with all reasonable pull requests as long as the guidelines below are met.

- The license header must be applied to all java source code files.
- IDE or system-related files should be added to the .gitignore, never committed in pull requests.
- In general, check existing code to make sure your code matches relatively close to the code already in the project.
- Favour readability over compactness.
- If you need help, check out the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) for a reference.
