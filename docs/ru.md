# Документация

* [Первая программа](#Первая-программа)
* [Переменные](#Переменные)
* [Функции](#Функции)
* [Списки](#Списки)
* [Типы](#Типы)
* [Условные конструкции](#Условные-конструкции)
    * [if / else](##if-else)
    * [case](##case)
* [Циклы](#Циклы)
* [Структуры](#Структуры)
* [Перечисления](#Перечисления)
* [HTTP модуль](#HTTP-модуль)
* [GUI модуль (Swing)](#GUI-модуль-swing)
* [Async модуль](#Async-модуль)
* [Спецификация языка](../spec/specification.ru.md)


# Первая программа
Первая программа: вывод в консоль hello world
В Ixion точкой входа служит функция `main`, именно там мы
и будем вызывать функцию для вывода текста в консоль. Для вывода
используется `print` или `println` из библиотеки `prelude`.
Стандарт prelude (о методах и их реализациях под разные бекенды)
описан в [документе]() (на данный момент документ приватный).

````scala
use <std>

def main() {
    std::println("Hello World")
}
````

# Переменные
Для создания переменной нужно воспользоваться 
ключевым словом `var`, далее идет имя переменной, оператор `=` и значение.

Пример 1:

````scala
use <std>

def main() {
    var h = "Hello "
    var w = "World"
    std::println(h + w)
}
````

Пример 2:

````scala
use <std>

def main() {
    var a = 10
    std::print(a)
}
````

# Функции

Сигнатура:

`def` имя (аргумент1: тип, аргумент2: тип): тип {..}

Пример функции, которая принимает два целых
числа и возвращает их сумму:

````scala
use <std>

def main() {
    std::println(sum(10, 2))
}

def sum(a : int, b : int): int {
    return a + b
}
````

# Списки

В Ixion не существует массивов, вместо этого используются списки - 
динамические массивы.

Список целых чисел и список строк:
````scala
def main(){
    var nums = [1,2,3,4,5]
    var names = ["Maxim", "Artyom", "Anton"]
}
````

Пустой список строк:
````scala
def main(){
    var my_list = string[]
}
````

Функция, которая возвращает список целых чисел:
````scala
use <std>

def main(){
    std::print(ret_list())
}

def ret_list() : int[] {
    return [1,2,3,4,5]
}
````

Получить элемент списка:
````scala
use <std>

def main(){
    var nums = [1,2,3]
    std::print(list_get(nums, 0))
}
````

# Типы

````scala

use <std>

type text = string

def main(){ 
    std::println(greeting("Artyom"))
}

def greeting(name : text) : text {
    return "Hello, " + name
}
````


# Условные конструкции

## if else

Пример 1:
````scala
use <std>

def main(){
    var flag = true
    if(flag) {
      std::print("yes :)")
    } else {
      std::print("no :(")
    }
}
````

Пример 2:
````scala
use <std>

def main(){
    var age = 18
    if(age >= 18) {
      std::print("hello!")
    } else if(age >= 16){
      std::print("go home")
    } else {
      std::print("go home kid")
    }
} 
````

## case

Пример pattern matching'a с алгебраическими типами

````scala
use <std>

type number = int | float

pub def main(){
    print_type(10)
    print_type(10.0f)
}

def print_type(num : number){
    case num {
        int i => std::println("value " + i + " is integer")
        float f => std::println("value " + f + " is float")
    }
}
````

# Циклы

# HTTP модуль

Для HTTP-запросов:

````scala
use <http>
use <std>

def main() {
    var body = http::get("https://example.com")
    std::println(body)

    var status = http::requestStatus("GET", "https://example.com", "")
    std::println(status)
}
````

Доступные функции:
- `get(url: string): string`
- `delete(url: string): string`
- `post(url: string, body: string): string`
- `put(url: string, body: string): string`
- `patch(url: string, body: string): string`
- `request(method: string, url: string, body: string): string`
- `requestStatus(method: string, url: string, body: string): int`
- `urlEncode(value: string): string`


# Async

Для фоновых задач.

````scala
use <std>
use <async>

pub def main() {
    var taskId = async::run(lambda (): void {
        async::sleep(300)
        std::println("Background task finished")
    })

    while (async::isRunning(taskId)) {
        std::println("Waiting...")
        async::sleep(100)
    }

    std::println("Done")
}
````

Доступные функции:
- `run(task): int`
- `runDelayed(delayMs: int, task): int`
- `isRunning(taskId: int): bool`
- `await(taskId: int): void`
- `cancel(taskId: int): bool`
- `sleep(ms: int): void`
