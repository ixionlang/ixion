<div align="center">
  <img src="assets/logo.svg" width="500" style="margin: 50px 0; padding: 20px 0; display: block;">
  
  Multi-paradigm compiled programming language for the jvm platform.
</div>


> [!IMPORTANT]
> Before installing the language, install JDK.

greeting in Ixion:
```scala
use <std>

def main(){
    var greeting = lambda(name : string) : string {
        return "Hello, " + name
    }
    
    std::print(greeting("Artyom"))
}
```


pattern matching:

```scala
use <std>

type number = int | float

def main(){
    print_type(10)
    print_type(10.0f)
}

def print_type(num : number){
    case num {
        int i => std::println("value " + i + " is integer")
        float f => std::println("value " + f + " is float")
    }
}

```


## Contributions
We will review and help with all reasonable pull requests as long as the guidelines below are met.

- The license header must be applied to all java source code files.
- IDE or system-related files should be added to the .gitignore, never committed in pull requests.
- In general, check existing code to make sure your code matches relatively close to the code already in the project.
- Favour readability over compactness.
- If you need help, check out the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) for a reference.
