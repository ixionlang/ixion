# Ixion Language Specification

**Edition:** Ixion Core 1.0, normative draft dated 19 October 2026.
**Russian version:** [Русский](specification.ru.md).
**Compiler status:** [implementation conformance](implementation-status.md).

## Contents

1. [Scope and conformance](#1-scope-and-conformance)
2. [Notation and execution model](#2-notation-and-execution-model)
3. [Source text and tokens](#3-source-text-and-tokens)
4. [Literals](#4-literals)
5. [Modules and program startup](#5-modules-and-program-startup)
6. [Names and scopes](#6-names-and-scopes)
7. [Type system](#7-type-system)
8. [Conversions and common types](#8-conversions-and-common-types)
9. [Type declarations](#9-type-declarations)
10. [Generics and type inference](#10-generics-and-type-inference)
11. [Bindings and immutability](#11-bindings-and-immutability)
12. [Functions and closures](#12-functions-and-closures)
13. [Expressions and evaluation order](#13-expressions-and-evaluation-order)
14. [Operators](#14-operators)
15. [Statements and returns](#15-statements-and-returns)
16. [Lists and strings](#16-lists-and-strings)
17. [Patterns](#17-patterns)
18. [Case matching](#18-case-matching)
19. [Equality and hashing](#19-equality-and-hashing)
20. [Predefined types and std](#20-predefined-types-and-std)
21. [Foreign boundary and library profiles](#21-foreign-boundary-and-library-profiles)
22. [Errors and diagnostics](#22-errors-and-diagnostics)
23. [Complete syntactic grammar](#23-complete-syntactic-grammar)
24. [Implementation requirements and language boundaries](#24-implementation-requirements-and-language-boundaries)

## 1. Scope and conformance

### 1.1. Subject

Ixion is a statically typed language with immutable data values, locally mutable
bindings, functions, and closed algebraic data types. This document defines the
source language, static validity, evaluation, the required `std` module, and the
boundary with the external environment.

This is a self-contained contract for the **target Core 1.0 edition**, not a list
of changes or a claim that every existing compiler build already implements every
rule. Compiler conformance is documented separately. Installation syntax, CLI,
IDE project formats, and generated class naming are outside the language.

### 1.2. Normative terms

**MUST**, **MUST NOT**, and **REQUIRED** indicate mandatory requirements.
**MAY** indicates an implementation choice. An unqualified “error” is a static
checking error; a “fault” is abnormal termination during evaluation. Examples
illustrate rules and do not introduce exceptions to them.

The Russian and English versions define one contract. Section numbers, grammar,
formulas, and signatures agree. A translation discrepancy is a documentation defect,
not permission to choose semantics. For formal constructs, the identical EBNF,
formulas, and operation tables take precedence; conflicting prose must be corrected.

### 1.3. Conforming implementations

An implementation accepts statically valid programs within its declared resource
limits and preserves their observable behavior. An invalid program MUST NOT execute
after checking has failed. Extensions are permitted only in an explicitly selected
profile and must not change the meaning of a valid Core program. A bounded coverage
analysis may refuse a proof under §18.5.

## 2. Notation and execution model

`T`, `U`, and `R` denote types; `e` an expression; `v` a value; and `Γ` an environment
of names and types. `Γ ⊢ e : T` means that the expression has type `T`.
`S ↝ T` denotes an allowed implicit conversion of a value of type `S` to `T`.
`≡` denotes type identity after alias expansion and union normalization.

Evaluation is sequential and eager: arguments are evaluated before entering a
function. An evaluation outcome is a normal value, a `return` transfer, a fault,
or divergence. Following a fault or `return`, remaining subexpressions are not
evaluated. Divergence means a computation that does not terminate.

Observable results include returned data, the sequence of external effects, and
the fault category. Object addresses, memory sharing, allocation counts, particular
hash codes, and the output ordering of independent external threads are not portable
program results. Immutability does not imply absence of I/O or other effects.

## 3. Source text and tokens

### 3.1. Encoding and positions

A source file is valid UTF-8. An initial U+FEFF is permitted and ignored; an invalid
byte sequence is an error. CRLF is one line break; LF and isolated CR are also line
breaks. Diagnostic line and column numbers start at 1; columns count Unicode code
points, with a tab counting as one column. Byte offsets may additionally be reported.

### 3.2. Identifiers

An identifier starts with `_` or a Unicode letter in category `Lu`, `Ll`, `Lt`,
`Lm`, `Lo`, or `Nl`. Subsequent positions may additionally contain `Mn`, `Mc`,
`Nd`, or `Pc`. Unicode 15.0 properties apply. No Unicode normalization is performed.
Name comparison is exact and case-sensitive. A lone `_` is a separate pattern token,
not a valid declaration name.

### 3.3. Whitespace and comments

Space U+0020, tab U+0009, and line breaks separate tokens and have no other meaning.
`//` begins a comment ending at a line break or EOF. `/*` begins a non-nested
comment ending at the first `*/`; EOF within it is an error. Comment sequences
inside a literal remain literal content.

The lexer chooses the longest applicable token. For example, `::`, `=>`, `..`,
`**`, `+=`, and `==` are recognized before their one-character prefixes.
Comments separate tokens: `a/*x*/b` is two identifiers, not `ab`.

### 3.4. Keywords and delimiters

```text
case def else enum false for if lambda let mut never new pub return
struct true type use while with
```

`new` is reserved but has no Core 1.0 syntactic construct.
`unit`, `void`, `any`, `bool`, `char`, `int`, `float`, `double`, `string`,
`Option`, and `Result` are protected predefined type names. They cannot be
redeclared or shadowed in the type namespace.

Punctuation and operators:

```text
( ) { } [ ] , : :: . .. =>
+ - * / % ** ! && || ^ | == != < <= > >=
= += -= *= /= %= ++ --
```

`;` is not a Core separator. Line breaks do not terminate expressions.
Commas are required between parameters, arguments, fields, variants, elements,
and branches. Trailing commas are permitted exactly where specified in §23.

## 4. Literals

### 4.1. Numbers

```ebnf
DIGIT       = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;
DIGITS      = DIGIT { DIGIT } ;
EXPONENT    = ("e" | "E") [ "+" | "-" ] DIGITS ;
INT_LIT     = DIGITS ;
FLOAT_LIT   = DIGITS ("." DIGITS [ EXPONENT ] [ "f" ] | EXPONENT [ "f" ] | "f") ;
DOUBLE_LIT  = DIGITS [ "." DIGITS ] [ EXPONENT ] "d" ;
```

Numbers are decimal; leading zeros do not select octal. Hexadecimal, binary,
`_` separators, uppercase suffixes, and a decimal point without following digits
are outside Core. `1..3` consists of `1`, `..`, and `3`, not a fractional literal.
A sign is a separate operator. A completed numeric token cannot be immediately
followed by an identifier continuation: `1e`, `1foo`, and `1d2` are lexical errors,
not adjacent tokens; a recognized numeric suffix is part of the numeric token.

`INT_LIT` has type `int`, ranging from −2147483648 to 2147483647. A positive token
2147483648 is allowed only immediately after unary `-` to spell the minimum `int`.
Any other out-of-range literal is an error, not literal overflow.

A fractional literal without `d` has type `float`; one with `d` has type `double`.
An exponent without a decimal point also makes a floating-point literal. The value
is rounded to the nearest representable value of its type, ties to an even
significand. Literal overflow to infinity is an error; underflow to zero is allowed.
NaN and infinity have no dedicated literals but can arise during computation.

### 4.2. Characters and strings

`CHAR_LIT` is enclosed in `'...'` and contains exactly one UTF-16 code unit after
decoding. `STRING_LIT` is enclosed in `"..."` and contains a finite sequence of
UTF-16 code units. Literal line breaks and an unescaped closing quote are forbidden
inside a literal.

| Escape | Value |
| --- | --- |
| `\0` | U+0000 |
| `\b`, `\t`, `\n`, `\f`, `\r` | U+0008, U+0009, U+000A, U+000C, U+000D |
| `\\` | Backslash |
| `\'`, `\"` | Single and double quotes |
| `\uHHHH` | One UTF-16 code unit specified by four hexadecimal digits |

`HHHH` permits `0–9`, `a–f`, and `A–F`. Unknown or incomplete escapes are errors.
Unicode escapes are processed inside a literal after tokenization, not before
lexical analysis of the entire file. Surrogate code units are allowed; a non-BMP
character occupies two string units and cannot directly fit in a `char`.
There is no string interpolation, raw string syntax, or implicit concatenation of
adjacent literals.

### 4.3. Boolean and unit values

`true` and `false` have type `bool`. `()` is the sole value of `unit`.
There are no source-language `null`, `undefined`, or values of type `never`.

## 5. Modules and program startup

### 5.1. Identity and imports

A module contains imports, type declarations, function declarations, and module
`let` declarations. Its identity is its canonical project-root-relative path
without `.ix`. `use <a/b>` resolves to `a/b.ix` and introduces qualifier `b`.
Path components are identifiers; `.` and `..` import path components are forbidden.
The execution environment supplies a project root fixed for the compilation.

Predefined modules `std`, `strings`, `http`, and `async` resolve in the profile
library and cannot be replaced by project files. Only `std` is required by Core;
the others are profiles under §21. An import does not copy declarations or introduce
unqualified names. Two imports with the same qualifier are an error, including
a duplicate import of the same module. Import aliases do not exist.

Signatures of all reachable modules are collected before checking bodies. Cyclic
imports for mutually referring functions and types are allowed. A public signature
must not depend on a private nominal type from its own or another module, including
inside a nested generic type or functional signature.

### 5.2. Exports

`pub` is allowed only on top-level `def`, `type`, and `let` declarations.
Without `pub`, a declaration is available only in its own module. Exporting a
structure exposes its type, constructor, and all fields. Exporting an `enum`
exposes its type and all variants with their fields. Private fields, hidden variants,
and extension of another module's `enum` do not exist. An alias exports a type name
but does not introduce a constructor value.

### 5.3. Initialization

Before calling `main`, modules are initialized by visiting imports in source order:
an unvisited module is marked “initializing”, its imports are visited, and its
`let` initializers are then evaluated in declaration order. Re-entering an
initializing module does not initialize it again. A completed module is marked
“initialized”. Each initializer runs exactly once.

Type and function declarations have no evaluation effect and are available before
binding initialization. An initializer's direct reference to a later binding in
its own module is an error. An indirect read of an uninitialized binding through
a function or an import cycle produces an initialization fault. It neither returns
a default value nor triggers lazy re-initialization. A fault stops program execution.
Initialization MUST NOT depend on filesystem enumeration order.

### 5.4. Entry point

The execution environment selects an entry module. It MUST contain exactly one
`def main()` with no type parameters or arguments and with result `unit` or no result
annotation. `pub` is optional. Other modules may contain their own `main`, which is
not called automatically. Command-line arguments are not part of the Core signature.
Normal completion of `main` means success; a fault means process failure.

## 6. Names and scopes

Types, values, and module qualifiers have separate namespaces. A structure
constructor occupies the value namespace using its type's name; an `enum` provides
variants in its type-qualified namespace. Repeating a name in one namespace within
one scope is an error. A type and an independent value may share a name if the type
does not introduce a conflicting constructor.

A module, function/lambda body, and each block introduce scopes. Function parameters
and declarations immediately within its body belong to the same scope. A local
binding is visible after its initializer has completed and until its scope ends.
Its initializer still resolves an outer binding of the same name. A type parameter
is visible in its declaration's signature and body; a value parameter only in the
body. A nested block may shadow an outer name. Recursive local bindings are not
introduced.

An unqualified name first resolves in the closest applicable namespace scope.
`module::name` refers only to an export of that module. `Type::Variant` and
`module::Type::Variant` resolve through the type namespace, not a value field.
`.field` never denotes module qualification.

Pattern bindings are visible in the guard and body of that branch, not in other
branches. A `for` binding exists only in its body and is newly bound each iteration.
The Core grammar does not allow local `type`, `def`, `pub`, or `use` declarations.

## 7. Type system

### 7.1. Types

```text
T ::= bool | char | int | float | double | string | unit | never | any
    | P | D[T1,...,Tn] | (T1,...,Tn) | T[] | (T1,...,Tn) => T
    | T1 | ... | Tn
```

`P` is a type parameter; `D` a nominal structure, sum, or profile-defined opaque
foreign type. Non-generic `D` does not require `[]`. The final line of the formula
denotes a union, rather than metagrammatical alternatives.

`bool` has two values; `char` has unsigned UTF-16 units 0…65535; `int` has signed
32-bit integers; `float` and `double` are IEEE 754 binary32 and binary64;
`string` has immutable UTF-16 sequences of length at most 2147483647.
`void` is identical to `unit`, not a separate type.

`never` has no values. `any` can hold any value while preserving its active dynamic
type. Implicit extraction from `any`, field access, indexing, and invocation are
forbidden. `never` cannot be the result of a normally completing function;
faults and divergence are not values.

### 7.2. Identity

Nominal types are identical only if their canonical module, declaration, and every
type argument agree. Two declarations with identical fields remain distinct.
Aliases are transparent and expanded before comparing types. A type parameter is
identified by its declaration and position, not merely its spelling.

Lists, tuples, and functions are structural: identity requires the same shape,
arity, and identical component types. Component order matters. Function parameter
names do not affect type identity. `(T)` groups a type; `(T,)` is a singleton tuple;
`()` in type position denotes `unit`.

### 7.3. Unions

`A | B` is an anonymous union. Normalization expands aliases, recursively flattens
unions, removes duplicates and `never`, and yields `any` if `any` occurs.
An empty union is `never`; a singleton union is its sole type. Alternative order
is immaterial. For example:

```text
(int | string) | int ≡ int | string
never | int ≡ int
any | int ≡ any
int | float ≢ float
```

Numeric alternatives do not subsume each other. A concrete union value has exactly
one active normalized alternative; unions do not introduce another user nominal
type. Normalization repeats after generic substitution: `T | int` becomes `int`
when `T = int`.

### 7.4. Finite values

Structures, tuples, lists, and ADTs contain finite values without cyclic data
references. A recursive type denotes the least set of such values. A structure
with an uninhabited field is uninhabited; a sum has only variants whose every field
is inhabited. An empty list exists for every `T`, including `never`. Function
types permit diverging/faulting functions and do not themselves become empty
because their result is `never`.

## 8. Conversions and common types

### 8.1. Directed compatibility

The following rules completely define implicit conversions, after parameter
substitution and type normalization:

1. `T ↝ T`.
2. `never ↝ T` for every `T`.
3. `T ↝ any`; there is no reverse rule.
4. Numeric widening: `char ↝ int ↝ float ↝ double`, including transitive steps.
5. For a union source, each alternative must independently convert to the target.
   The active value converts according to its own alternative.
6. For a union target and a non-union source, select an identical alternative if
   present; otherwise there must be exactly one alternative admitting conversion.
   Zero or multiple candidates are an error.

Rule 3 precedes union decomposition. Numeric widening creates a value of the target
numeric type, rather than merely changing its annotation. Rounding follows §14.2.
`bool` does not participate in the numeric chain.

```text
int ↝ int | float       // exact int alternative
int ↝ float | string    // unique widening to float
int ↛ float | double    // ambiguous widening
int[] ↛ float[]         // no container conversion
```

### 8.2. Invariance

There is no implicit component conversion of an existing list, tuple, structure,
sum, or function. `Box[int] ↛ Box[any]`; `(int) => int` does not become
`(int) => float`. An expected type may guide **construction** of a literal or lambda:
`let xs: float[] = [1, 2]` widens elements before inserting them. This does not
permit reinterpreting an existing `int[]`.

### 8.3. Common result types

`join(T1,...,Tn)` first removes `never`: no types yields `never`; one distinct type
yields that type; all numeric types yield the widest in the numeric chain;
otherwise the result is the normalized union. `any` appears only if it already
occurred in the input types. No common type is sought inside existing containers.

With an expected type, each resulting expression is checked with that expectation
and converted to it. Without one, `join` applies and each result converts to the
chosen type. This rule governs `case` results, list elements, and lambda result
inference.

## 9. Type declarations

### 9.1. Aliases

`type Name[P,...] = T` declares a transparent alias with fixed type argument arity.
It does not create a constructor or a new kind of value. Forward references are
allowed. Cyclic alias expansion is an error even when nominal declarations are
involved; nominal recursion must pass through fields rather than require infinite
expansion of an alias definition.

### 9.2. Structures

```ixion
type Point = struct { x: int, y: int }
type Box[T] = struct { value: T }
```

Field names are unique. Every field is required and immutable; there are no default
values, inheritance, methods, setters, or partially initialized objects.
`Point(1, 2)` takes arguments in field declaration order. An empty structure is
constructed as `Name()`. A constructor's type is a function from the field types
to the structure type.

Access `e.field` requires the static type of `e` to be one specific structure
containing that field. Common fields of several union alternatives are not opened
automatically; narrowing requires a pattern. Structure identity follows its
declaration regardless of field order or similarity to other declarations.

### 9.3. Sums

```ixion
type Delivery = enum {
    Pickup,
    Courier(address: string),
}
```

A sum has a finite nonempty set of unique variants. A variant has zero or more
fields, whose names are unique within that variant. A fieldless variant is spelled
`Pickup`, not `Pickup()`. A variant with fields has a nonempty field list.

A fieldless value is `Delivery::Pickup`; a value with fields is
`Delivery::Courier("A")`. A generic sum specializes its owner:
`Option[int]::Some(1)`. A variant is not a separate type and cannot be extended
by another module. Fields are accessible only by destructuring;
`delivery.address` is an error. A constructor with fields can be a function value
after its type arguments are resolved; a fieldless variant is already a sum value.

### 9.4. Recursion and positivity

Recursive and mutually recursive structures/sums are allowed, including recursive
occurrences with changed type arguments. Lack of a finite value does not itself
invalidate a nominal declaration.

Every recursive cycle of nominal dependencies MUST be positive.
Traversing into a function parameter reverses the sign; entering its result
preserves it. Lists, tuples, and unions preserve the sign. For a user generic type,
a parameter's sign is computed through all fields and variants with substitution;
occurrence with both signs is mixed. A recursive cycle returning to its declaration
through a negative or mixed position is forbidden. An unused parameter creates no
dependency. Analysis operates on a finite declaration graph, propagating signs to
a fixed point, not by infinitely unfolding concrete generic instances.
This rule does not introduce assignment covariance.

## 10. Generics and type inference

### 10.1. Parameters

Type parameters follow the declaration name: `def f[T](x: T): T`,
`type Box[T] = ...`. Names are unique within the declaration. There are no default,
variadic, constrained/trait, or higher-kinded type parameters. Explicit specialization
supplies every argument with exactly the declared arity. A type argument may be a
compound type, including a union or `never`. Foreign resources are not valid
arguments of ordinary data-generics (§21).

A generic function is checked once with abstract parameters. Only operations valid
for every permitted parameter value are allowed: an unrestricted `T` cannot be added,
called as a function, or compared by `==`. Recursion may use a different specialization
of the same function. Polymorphism is rank one: parameters/fields cannot store a
value with its own `forall` quantifier.

### 10.2. Contextual inference

Inference is local to an expression and uses an expected type from a binding
annotation, function result, call argument, or constructor field. Public and ordinary
`def` declarations require parameter types; an omitted `def` result means `unit`.
Local bindings are not implicitly generalized.

Explicit arguments fix parameters. Exact matching of an expected result against a
nominal/container shape creates parameter equations. Expectation `any` creates no
constraints. A bare inferred result parameter is also fixed equal to the expected
type unless that expectation is `any`.
For remaining parameters, arguments create constraints: a bare parameter
receives a lower bound from an argument type; beneath an invariant constructor,
component types must be exactly equal. Multiple lower bounds combine through `join`;
equations must agree and satisfy the bounds.

After solving, all arguments are rechecked with substituted parameters. A successful
preliminary inference alone cannot justify accepting a call. An unresolved parameter,
conflicting equations, or multiple allowed union injections is an error; inserting
`any` to suppress it is forbidden.

Context-dependent `[]`, fieldless variants, and lambdas may be checked after
constraints from other arguments are obtained; this changes **checking** order,
never evaluation order. If an allowed type cannot be inferred locally, the programmer
MUST supply an annotation or specialization. Constructors follow the same rules,
not a separate unconstrained guessing procedure.

### 10.3. Specializing function values

A generic function or constructor reference used as a value requires explicit type
arguments or a unique expected monomorphic function type. Once specialized, it is an
ordinary function value. There is no partial application, automatic currying, or
user-defined `def` overloading.

## 11. Bindings and immutability

`let x [: T] = e` binds the evaluated value to an immutable name.
`let mut x [: T] = e` permits local reassignment. Both forms require an initializer,
which may run at runtime. Without an annotation, the initializer determines the
type of the binding for all subsequent assignments.

`let mut` is allowed only in a function, lambda, or nested block thereof. Parameters,
`for` variables, pattern names, and captured values are always immutable.
Assignment can target only an accessible name declared with `let mut`. Structure fields,
variant payloads, tuple components, list elements, and list length do not change
after construction. Iteration, aliasing, and function passing provide no additional
mutation path. Immutability is transitive for language data.

`x = e` evaluates the right-hand side once, converts it to the fixed type of `x`,
and then replaces its value. `x op= e` first reads the old `x`, then evaluates `e`,
applies `op`, converts the result back to the type of `x`, and stores it. An impossible
back-conversion is an error. `++` and `--` are equivalent to adding/subtracting `1`
of the same numeric type for `int`, `float`, and `double`; they are forbidden for
`char`, `bool`, containers, and other types.

All assignment and postfix-update forms are **valueless statements**, not expressions.
`f(x++)`, `return x = 1`, chained `a = b = 1`, and writes to fields/indices are
forbidden. A fault in the right-hand side prevents the assignment.

## 12. Functions and closures

### 12.1. Declarations and calls

`def f[P,...](p: T,...): R { ... }` declares a function. Parameters are positional,
required, and unique. There are no named/default/variadic arguments, overloads,
or implicit callable-object conversions. An omitted result annotation means `unit`.

A call first evaluates the callee expression, then arguments left to right. Each
argument converts to the corresponding parameter type before body entry. Calling
a non-function value is an error. Top-level functions and specialized constructors
can be function values.

### 12.2. Lambdas

```ixion
lambda (value: int): int { return value + 1 }
```

Type `(A, B) => R` includes arity, parameter types, and result; parameter names do
not affect identity. A lambda always has a block. A parameter annotation may be
omitted if a unique expected type determines it. Without context, every parameter
requires an annotation. An explicit result annotation is checked as written;
otherwise an expected result or the `join` of this lambda's explicit `return` types
is used, excluding returns in nested lambdas. No returns with possible normal body
completion infer `unit`. All-faulting/diverging paths may infer `never`. A result
whose body may complete normally MUST be exactly `unit` (§15.4).

### 12.3. Capture

Free local values are captured when the lambda is created, left to right in order
of first free-name occurrence in its text. Capture takes the current value,
including the value of an outer mutable binding; it does not retain a reference to that variable's
mutable cell. The captured name is immutable inside the lambda. Outer reassignment
does not change an existing closure. Module functions and types do not create local
captures; module bindings are read under the initialization rules.

Lambda arity is not limited to four. Platform resources may limit arity and stack
size, but implementation limits MUST be published and excess diagnosed.

## 13. Expressions and evaluation order

### 13.1. Primary and compound expressions

Expressions are literals, value names, specializations, constructors, lambdas,
grouping, lists, tuples, calls, field reads, indexing, unary/binary operations,
`with`, and `case`. Declarations, assignments, `if`, `while`, `for`, and `return`
are not expressions.

Fields, list/tuple elements, and constructor arguments evaluate left to right
exactly once. Grouping `(e)` preserves the type, value, and effects of `e`.
`(e,)` creates a singleton tuple. Required component conversions occur before
insertion into a container.

### 13.2. Structure updates

`base with { field = e, ... }` requires a specific structure type for the base.
The base evaluates once, followed by update expressions in source order and in
the original scope. Updates do not see the results of earlier updates. Unspecified
fields retain their values; the structure type and its arguments do not change.
Duplicate/unknown fields are errors. `with {}` is allowed and preserves the value.
Separate allocation is not guaranteed. This form cannot update ADTs, tuples, or
lists. A nested path `a.b = ...` is not introduced within `with`; explicit nested
updates are used instead.

### 13.3. Dynamic type information

A value entering `any` or a union preserves the semantic information required by
§17: nominal declaration and generic arguments, list/tuple types, the complete
monomorphic function signature, and the active union alternative. Java erasure does
not change language rules. Tag serialization and box representations are unspecified.

## 14. Operators

### 14.1. Precedence

From lowest to highest; `with` and binary operators other than `**` associate left.
Comparisons and range do not form chains.

| Level | Operations | Associativity |
| --- | --- | --- |
| 1 | `with { ... }` | left |
| 2 | `^` | left |
| 3 | `||` | left |
| 4 | `&&` | left |
| 5 | `== != < <= > >= ..` | non-associative |
| 6 | `+ -` | left |
| 7 | `* / %` | left |
| 8 | `**` | right |
| 9 | prefix `+ - !` | right |
| 10 | call, `.field`, `[index]`, specialization | left |

Thus `-2 ** 2` means `(-2) ** 2`, and `2 ** 3 ** 2` means `2 ** (3 ** 2)`.
`a < b < c` is forbidden; use `a < b && b < c`.

### 14.2. Numeric operations

`+ - * / % **` require numeric operands. `char` first widens to `int`, then the
common numeric type is selected. `bool` is not allowed. Unary `+`/`-` require a
number and likewise promote `char` to `int`.

`int` arithmetic wraps modulo 2^32, interpreting the result as signed. Division
rounds toward zero; remainder has the dividend's sign. `MIN_INT / -1` yields
`MIN_INT`, and `MIN_INT % -1` yields 0. Integer division/remainder by zero faults.
Negating `MIN_INT` yields `MIN_INT`.

`float`/`double` use IEEE 754 at their respective precision, rounding to nearest,
ties to even, after every operation. Overflow yields infinity; underflow may yield
a subnormal or signed zero. Division by zero follows IEEE 754, not integer fault
rules. `%` uses a quotient truncated toward zero, as JVM remainder, not the IEEE
nearest-quotient remainder function. Numeric widening rounds to the result precision
and need not retain all significant digits; `char → int` is always exact.

For `int ** int`, a negative exponent faults; zero yields 1, including `0 ** 0`;
a positive exponent uses integer exponentiation with signed 32-bit wraparound.
For common type `float`/`double`, the result is JVM `StrictMath.pow` on arguments
widened to binary64, then rounded to the common type. Intermediate `double` use
for ordinary binary32 arithmetic is forbidden.

### 14.3. Logic and strings

`!`, `&&`, `||`, and `^` require and return `bool`. `&&` evaluates its right operand
only when the left is `true`; `||` only when the left is `false`. `^` evaluates both
and returns whether their Boolean values differ. `+` on two `string` values produces
their concatenation; mixing a string and another type has no implicit string conversion.

### 14.4. Comparisons and range

`< <= > >=` accept numbers, widen them as arithmetic does, and return `bool`.
These comparisons with NaN yield `false`. Ordering strings, structures, `bool`,
or other data with these operators is undefined and statically forbidden.
`==`/`!=` are specified in §19.

`start .. stop` requires two `int` values and returns immutable `int[]` containing
`start, start+1, ..., stop-1`. Bounds evaluate once, left to right. When
`start >= stop`, the result is empty. Length is computed without `int` overflow;
inability to represent or allocate the result is a resource fault, not counter
wraparound or an infinite loop.

## 15. Statements and returns

### 15.1. Sequences and blocks

Statements execute in order. A declaration creates a binding; assignment replaces
its value; an expression statement evaluates and discards its result. Any result,
including `Result`, may be discarded: there is no automatic obligation to handle
it. This does not relax completeness of a `case` that is explicitly written.

An ordinary function block does not implicitly return its final expression.
A special `case` branch block yields its last expression statement's value if
normal execution reaches it. An empty block, or one ending in a declaration,
assignment, or control statement, yields `unit` on normal completion. If normal
completion is impossible, its result type is `never`. A `return` within any such
block belongs to the nearest function/lambda.

### 15.2. If and while

`if condition { ... } else { ... }` requires `bool` and executes exactly the
selected body. `else` may be absent or contain another `if`. Body-local names do
not escape their scope. A false condition without `else` simply continues execution.

`while condition { ... }` evaluates the condition before each iteration, stops
when it is `false`, otherwise executes the body and repeats the test. The condition
has type `bool`. A `return` or fault prevents another test.

### 15.3. For

`for name : expression { ... }` requires `T[]` or `string`. The iterable expression
evaluates exactly once. A list is traversed in index order; a string in UTF-16 code
units of type `char`. Each iteration creates a new immutable binding. An empty
sequence does not execute the body. Reassigning the original binding does not change
the sequence being traversed. This also defines lambda capture of the loop name:
the element of that iteration is captured.

Core has no `break`, `continue`, labels, do-while, or C-style `for`.

### 15.4. Return and completion checking

`return e` first evaluates `e`, converts its result to the nearest function/lambda's
result type, then immediately transfers it to the caller. `return ()` explicitly
returns `unit`. A line break after `return` does not terminate it; an expression
is required. `return` outside a function/lambda is forbidden.

Static normal-completion checking uses these rules:

- `return` and evaluation of a `never` expression do not complete normally.
- A sequence can complete normally only if every statement along a reachable path
  can do so.
- An `if` without `else` admits normal completion; one with `else` does so if either
  branch does after a normally evaluated condition.
- An exhaustive `case` does not complete normally if none of its bodies do.
- `while` and `for` conservatively admit normal completion; a literal `true` does
  not exempt a function from result checking.
- A declaration or assignment does not complete normally if an evaluated
  subexpression has type `never`.

A body with non-`unit` result MUST NOT admit reaching its end normally. A `unit`
body implicitly returns `()` when reaching its end. Unreachable statements after
`return` may cause a warning but are not mandatory Core errors; unreachable `case`
branches are errors (§18).

## 16. Lists and strings

### 16.1. Literals and length

`[e1,...,en]` creates an immutable homogeneous list. With expected type `T[]`, each
element is checked and converted to `T`. Without an expectation, the element type
is the `join` of element types. An empty literal without context is an error;
`never[]` is not inferred automatically. An explicit or expected type allows an
empty list, including `never[]`.

List length is an `int` from 0 to 2147483647. Elements, length, and ordering do not
change. Implementations may copy or share only immutable storage. Modifying an
incoming JVM array after list construction or removing through an external iterator
MUST NOT change the language list.

### 16.2. List indexing

`xs[index]` requires `xs: T[]` and `index: int`; its result is `Option[T]`.
The base and then the index each evaluate once. For `0 <= index < len(xs)`,
it returns `Some` of the element; otherwise `None`, including for negative indices.
There is no implicit extraction of the `Option` payload. The rule is equivalent
to `std::get(xs, index)` and does not require importing `std` for the syntax.
An index cannot be assigned to; updates use `std::updated`.

### 16.3. Strings

A string is immutable; `len` counts UTF-16 units, not grapheme clusters or bytes.
**String** indexing, unlike list indexing, retains a separate contract:
`text[index]` has type `string` and contains one UTF-16 unit. An out-of-range access
produces a bounds fault. It does not automatically return `Option` or `char`.
Sequential `for` traversal yields `char`. Isolated surrogates are preserved without
automatic normalization.

## 17. Patterns

### 17.1. General rules

A pattern is checked against the subject's static type and either fails or creates
a finite set of immutable bindings. Computations, function calls, arbitrary
expressions, and overloaded user equality are forbidden in patterns. Repeating
a binding name within one pattern is an error, not an equality test.

Patterns do not convert the subject or perform numeric widening. Nested patterns
are checked left to right; bindings are unavailable to the guard/body until the
whole pattern succeeds. Omitted fields in a structure pattern are ignored.

| Form | Condition and bindings |
| --- | --- |
| `_` | Always matches; introduces no name |
| `name` | Always matches; binds the subject with its static type |
| literal | Same literal type and value under §19, without numeric widening |
| `-number` | A signed numeric literal, not an arbitrary unary operation |
| `Type::Variant(p,...)` | Same concrete sum type, variant, and matching fields |
| `Type::Variant` | The corresponding fieldless variant |
| `Type { field: p,... }` | Same concrete structure type and matching selected fields |
| `(p1,p2,...)` | Same tuple arity and matching components |
| `(p,)` | A singleton tuple |
| `[p1,...,pn]` | Exactly n elements, all matching |
| `[p1,...,pn,..tail]` | At least n elements; binds the tail as `T[]` |
| `[.._]` | Any list, without a tail binding |
| `T name` | Semantic type test under §17.3; a binding of type T |

`()` matches `unit`; `(p)` groups a pattern. A list permits at most one rest,
only at the end. A rest tail is immutable and does not expose a mutable view.
Duplicate/unknown structure fields and incorrect tuple/variant arities are errors.
OR-, as-, and range-patterns, and guards inside nested patterns, are not introduced.

### 17.2. Constructor resolution

A qualified pattern `module::Type[Args]::Variant` identifies an exact nominal type.
Without explicit type arguments, parameters are inferred from the expected subject
or field type. If a subject admits several different instances of one generic type
and they cannot be determined uniquely, specialization or a preceding type pattern
is required. Variant names are not searched globally.

Patterns do not introduce GADT refinement: selecting a variant does not alter the
enclosing function's generic parameters. Payload types follow ordinary declaration
parameter substitution only. Inability to determine them uniquely is an error.

### 17.3. Type patterns and dynamic types

For normalized type `T`, define `Alt(T)` as its alternative set, with
`Alt(never) = ∅` and `Alt(any)` containing all permitted semantic value types.
For a non-union type, `Alt(T) = {T}`. A type pattern matches when the value's active
dynamic type belongs to `Alt(T)`. Numeric compatibility does not participate:
`float x` does not match a dynamic `int`.

Full invariant arguments are tested: `Option[int]` differs from `Option[string]`,
`int[]` from `any[]`, and `(int) => int` from `(int) => any`. `T` may contain the
current function's parameters; runtime substitution and normalization precede
testing. A union type pattern accepts each of its alternatives; `any name` covers
all values, while `never name` matches none.

For a statically union-typed subject, conversion under §8 determines the active
alternative. `any` preserves the packed value's active type; a static union does
not become an additional observable box type. A pattern with provably empty
intersection with the static domain is unreachable (§18).

## 18. Case matching

### 18.1. Evaluation

```ixion
case value {
    Option::Some(number) if number > 0 => number,
    Option::Some(_) => 0,
    Option::None => -1,
}
```

The subject evaluates exactly once before any branch. Branches are considered in
order: a failed pattern proceeds to the next branch. On success, bindings are
created and the guard, if present, evaluates once. A guard must be `bool`.
If false, bindings are discarded and search continues. If true or absent, the body
runs and its outcome becomes the `case` outcome. No other body evaluates. A guard
fault is not a pattern failure and does not continue the search. Guard effects
remain observable even when it returns `false`; there is no effect rollback.

### 18.2. Result type

An expression body yields its value; a block yields its result under §15.1.
With an expected type, all normally completing bodies are checked against it.
Without one, the result is the `join` of body types. Early-returning, faulting,
or `never` branches do not enlarge the result. If there are no normal results,
the `case` type is `never`. `case` may be used as an expression statement.

### 18.3. Exhaustiveness

For subject type `S`, let `Values(S)` denote its possible finite values and
`Cover(p,S)` the subset matched by a pattern without a guard. An exhaustive `case`
MUST satisfy:

```text
Values(S) ⊆ ⋃ Cover(pi,S)    for branches i without guards
```

A guard does not add static coverage, even `if true`. Coverage accounts for nested
products, variants, concrete type arguments, `bool`, `unit`, empty/nonempty lists,
and impossible fields. Enumerating individual literals does not exhaust an open
numeric or string domain; a covering form is required.

`Option[never]` has only `None`; `Some` contributes no possible values.
If `Values(S)` is provably empty, an empty `case e {}` of type `never` is permitted.
This does not suppress evaluation of `e`: its fault or divergence is preserved.

### 18.4. Reachability

Branch i is unreachable if the following remainder is provably empty:

```text
Cover(pi,S) \ ⋃ Cover(pj,S)    for j < i without guards
```

Such a branch is an error, even if it has its own guard. Preceding guarded branches
do not reduce the remainder; their conditions are not proved true. The compiler
need not prove guard reachability using arithmetic facts.

### 18.5. Termination of checking

Analysis of recursive and generic types MUST terminate. An implementation may use
pattern matrices, finite graphs, memoization, and published depth/work budgets.
An unknown result cannot justify marking a branch unreachable or accepting an
incomplete `case`. Instead, `match.indeterminate` requests a simpler match or a
covering branch. Known incompleteness produces `match.non_exhaustive` and a witness
pattern denoting a nonempty uncovered region.

For symbolic generic parameters, proofs MUST hold for every permitted substitution.
Normalization can merge alternatives; checking must not rely solely on erased JVM
classes. `T | int` cannot be assumed disjoint when `T` could be `int`.

## 19. Equality and hashing

### 19.1. Eligibility

A type has property `Eq` if it is `bool`, `char`, `int`, `float`, `double`, `string`,
`unit`, `never`, or a product/list/sum/union whose reachable components all have
`Eq`. For recursive data this is checked on a finite graph: a positive cycle does
not itself invalidate the property. Functions, `any`, foreign resources, and
unrestricted type parameters do not have `Eq`. An impossible variant with no values
does not require payload equality.

`==` and `!=` require a common type with `Eq`. Two numbers use their common numeric
type; otherwise types must agree or inject uniquely into an already known common
union. There is no implicit common-`any` search to compare unrelated nominal types.
`!=` is the Boolean negation of `==`.

### 19.2. Values

- Numbers compare after permitted widening. Floating-point NaN equals nothing,
  including itself; `-0.0 == 0.0` is true.
- `bool` compares by value, `char` by UTF-16 unit, and `string` by unit sequence,
  without normalization or locale dependence.
- `unit` always equals `unit`.
- Structures and tuples compare componentwise in field/component order.
- An ADT requires the same nominal type, arguments, variant, and equal variant fields.
- Lists require equal length and elementwise equality in index order.
- A union requires the same active semantic alternative, then equality of its values.
  `int(1)` and `float(1)` within `int | float` are unequal.

Checking stops at the first difference. Equality does not invoke user effects.
There is no reference identity comparison or user-defined operator overload.

### 19.3. Hashing

`std::hash[T](value: T): int` is available only with proven `Eq(T)`. Equal values
of the same type MUST have equal hashes within an execution. Signed zeros hash
equally; NaN does not impose a self-equality obligation. Structural data hashes
depend on contents, not addresses. Algorithm, seed, and cross-process stability
are unspecified. `Eq` is an internal checking property, not user trait syntax.

## 20. Predefined types and std

### 20.1. Option and Result

Their logical declarations are predefined without an import:

```ixion
type Option[T] = enum { None, Some(value: T), }
type Result[T, E] = enum { Ok(value: T), Err(error: E), }
```

This describes built-in declarations, not legal module redeclarations. Their type
identities agree across all program modules. Variants follow ordinary construction,
generic substitution, and matching rules. `Result` does not catch faults; `Option`
does not add nullable values. There is no automatic `?`, implicit unwrap, or
exceptional `return Err` mechanism.

### 20.2. Required module signatures

The following is a library interface, not source functions without bodies. The
only parameter-shape overload is the two built-in `len` signatures. Explicit
`len[T]` selects the list signature.

```text
std::print(value: any): unit
std::println(value: any): unit
std::len[T](values: T[]): int
std::len(text: string): int
std::append[T](values: T[], value: T): T[]
std::concat[T](left: T[], right: T[]): T[]
std::get[T](values: T[], index: int): Option[T]
std::updated[T](values: T[], index: int, value: T): Option[T[]]
std::map[A, B](values: A[], transform: (A) => B): B[]
std::filter[T](values: T[], predicate: (T) => bool): T[]
std::fold[A, B](values: A[], initial: B, transform: (B, A) => B): B
std::range(start: int, stop: int): int[]
std::hash[T](value: T): int    // requires Eq(T)
std::panic(message: string): never
```

### 20.3. Operation contracts

| Operation | Normal result |
| --- | --- |
| `len` | Number of elements/UTF-16 units |
| `append` | Original elements followed by the new element |
| `concat` | All left elements followed by all right elements |
| `get` | `Some` at a valid index, otherwise `None` |
| `updated` | `Some` of a list with one replacement, otherwise `None` |
| `map` | Callback results in original order |
| `filter` | Elements whose predicate returns `true`, in original order |
| `fold` | Sequential accumulator starting at initial |
| `range` | Exactly the result of `start .. stop` |
| `hash` | A hash under §19.3 |
| `panic` | No normal result |

`map`, `filter`, and `fold` invoke the callback left to right exactly once for each
processed element; they are neither parallel nor lazy. Callback faults/divergence
prevent processing later elements. Empty lists invoke no callback; `fold` returns
initial. `fold` passes the accumulator first and the element second.

`updated` evaluates every argument, including value, before testing bounds.
Every operation preserves its input lists, including a failed update. The `map`
result type and empty-result types are not lost due to absent elements. There are
no asymptotic, copying, or structural-sharing requirements.

`print` produces a diagnostic representation and writes it to standard output;
`println` additionally writes a line break. Strings print without quotes, `bool`
as `true`/`false`, `unit` as `()`, and `int` in decimal. Container, floating-point,
and resource formatting is not a serialization format. Output calls within one
thread retain their order. I/O failure is an external fault. `panic` passes its
message to environment diagnostics and faults the computation.

## 21. Foreign boundary and library profiles

### 21.1. Resource values

All values intrinsic to Core are resource-free. Resource values arise only through
an external profile; the following requirements constrain such extensions and do
not add source constructs to the minimal core.

A profile may declare opaque resource types and foreign functions with explicit
Ixion signatures. Foreign adapter declarations themselves have no Core source
syntax. Their names are exported through ordinary modules. Reflecting a Java
signature alone does not establish a correct Ixion signature.

A resource is a value with externally mutable state. It may be held in a local
binding and passed to profile operations, but not stored in a module binding,
structure, ADT, tuple, or list. A closure capturing a resource is also resource-bearing.
Packing it in `any` does not remove that property.

Implementations track data admissibility beyond surface types: insertion into a
container or passing an ordinary data-generic argument MUST establish that the
actual value is resource-free. An unknown native API result is not automatically
safe data. A profile must provide certified copying/conversion or reject the
transfer. This is not an ownership system: local resource aliasing is not forbidden.

### 21.2. Adapters

A nullable foreign API result is represented as `Option[T]`: JVM `null` becomes
`None`; non-null becomes `Some` after adaptation. A mutable collection is copied
into an Ixion list before entering the language. A language list cannot expose
mutable backing storage to foreign code. External mutation may alter a resource,
but not an already obtained data value. A profile declares failure classification
and resource release; Core does not specify GC finalization, RAII, locking, or
thread safety of the external system.

### 21.3. Library profiles

`strings`, `http`, `async`, file input, and console reading are not required in a
minimal Core implementation. A profile MUST publish complete signatures, nullable/
resource contracts, encoding, effects, and ordering guarantees. For `async`, §2's
sequential guarantee applies within a thread; the profile specifies inter-thread
interaction. Particular legacy HTTP/async functions do not automatically define
the language contract.

## 22. Errors and diagnostics

### 22.1. Static errors

An implementation MUST detect the following categories before execution. These
are stable specification categories, not mandatory verbatim message text or
specific Kotlin/Java exception class names.

| Category | Cause |
| --- | --- |
| `lex.invalid` | Invalid encoding, token, escape, unterminated literal/comment |
| `parse.invalid` | Grammar, required-comma, or construct-boundary violation |
| `name.unknown`, `name.duplicate` | Unresolved or redeclared name |
| `module.resolve`, `module.visibility` | Unresolved import, qualifier collision, export violation |
| `type.arity`, `type.mismatch` | Wrong type argument count or incompatible types |
| `type.inference` | Unresolved/ambiguous inference, context-free empty literal |
| `type.alias_cycle`, `type.negative_recursion` | Forbidden recursion |
| `call.arity`, `call.not_callable` | Invalid invocation |
| `mutation.immutable`, `mutation.target` | Reassigning an immutable binding, field/index writes |
| `operator.invalid` | Operation undefined for operand types |
| `pattern.invalid`, `pattern.ambiguous` | Invalid field/variant/arity/binding/pattern inference |
| `match.non_exhaustive`, `match.unreachable` | Incomplete matching or unreachable branch |
| `match.indeterminate` | Coverage unproven within declared budget |
| `return.invalid`, `return.missing` | Return outside a function, wrong result, missing result path |
| `initialization.forward` | Direct reference to a later module binding |
| `resource.escape` | A resource may enter immutable data |

A diagnostic identifies the module, category, and a nonempty source span; EOF errors
may use a zero-width EOF position. Type errors report expected and actual types;
redeclarations also identify the original declaration. Incomplete matches follow
§18.5. Implementations choose message language and formatting. An internal generator
failure is not a valid substitute for diagnosing invalid source programs.

### 22.2. Faults and limits

Normative fault classes are arithmetic, string bounds, initialization, explicit
`panic`, foreign operation, and platform resource exhaustion. A fault produces no
value of the requested type and does not automatically become `None`/`Err`.
Core has no `try`/`catch`/`throw`; a profile may supply an explicit safe adapter
returning `Result` for specified foreign failures.

Memory/stack exhaustion or excessive source, signature, or result sizes may
terminate compilation/evaluation abnormally. Coverage-analysis limits are published
separately. Limits do not permit silent type changes, effect reordering, mutable
data exposure, or execution of an unverified program.

## 23. Complete syntactic grammar

### 23.1. Conventions and disambiguation

In EBNF, `=` defines a rule, `|` alternation, `{ ... }` zero or more repetitions,
and `[ ... ]` an optional part. Quotes denote terminals. `IDENT`, number/string
tokens, and `EOF` are defined by §§3–4, not syntactic-level productions.
`_` is not an `IDENT`. Machine-readable copy: [grammar.ebnf](grammar.ebnf).

Lexical and static constraints supplement the grammar: for example, parentheses
on a variant require fields, `pub` is top-level only, and an empty `case` requires
an uninhabited subject. Function arrows associate right: `(A) => B | C` is a
function returning `B | C`; a union of functions requires grouping.

When type application competes with indexing, `name[Types]` is a specialization
only on a resolved generic function/constructor reference or before `::Variant`.
On an ordinary value binding it is indexing. Grouped `(value)[index]` always means
indexing. Explicit type arguments cannot be appended to an arbitrary computed result.

At pattern start, qualified constructors and structure forms take precedence,
followed by a type pattern; a lone identifier is a binding. At statement start,
`IDENT` immediately followed by an assignment operator selects assignment rather
than an expression. An expression consumes its maximal continuation under precedence
and enclosing delimiters; line breaks are not delimiters. The last expression
statement in a branch block is classified as its result after parsing the statements.

### 23.2. Productions

```ebnf
module              = { use_decl | declaration } EOF ;
use_decl            = "use" "<" IDENT { "/" IDENT } ">" ;
declaration         = [ "pub" ] ( type_decl | function_decl | module_binding_decl ) ;

type_decl           = "type" IDENT [ type_parameters ] "=" type_definition ;
type_definition     = struct_definition | enum_definition | type_expr ;
type_parameters     = "[" IDENT { "," IDENT } [ "," ] "]" ;
struct_definition   = "struct" "{" [ fields ] "}" ;
fields              = field { "," field } [ "," ] ;
field               = IDENT ":" type_expr ;
enum_definition     = "enum" "{" variant { "," variant } [ "," ] "}" ;
variant             = IDENT [ "(" fields ")" ] ;
function_decl       = "def" IDENT [ type_parameters ] "(" [ fields ] ")"
                      [ ":" type_expr ] block ;
module_binding_decl = "let" binding ;
binding_decl        = "let" [ "mut" ] binding ;
binding             = IDENT [ ":" type_expr ] "=" expression ;

type_expr           = function_type | union_type ;
function_type       = "(" [ type_items ] ")" "=>" type_expr ;
union_type          = postfix_type { "|" postfix_type } ;
postfix_type        = type_atom { "[" "]" } ;
type_atom           = type_name [ type_arguments ] | "never" | "(" ")"
                    | "(" type_expr ")" | "(" type_expr "," [ type_items ] ")" ;
type_name           = IDENT [ "::" IDENT ] ;
type_arguments      = "[" type_items "]" ;
type_items          = type_expr { "," type_expr } [ "," ] ;

block               = "{" { statement } "}" ;
statement           = binding_decl | assignment | return_stmt
                    | if_stmt | while_stmt | for_stmt | block | expression ;
assignment          = IDENT assign_op expression | IDENT ( "++" | "--" ) ;
assign_op           = "=" | "+=" | "-=" | "*=" | "/=" | "%=" ;
return_stmt         = "return" expression ;
if_stmt             = "if" expression block [ "else" ( if_stmt | block ) ] ;
while_stmt          = "while" expression block ;
for_stmt            = "for" IDENT ":" expression block ;

expression          = update_expr ;
update_expr         = xor_expr { "with" "{" [ updates ] "}" } ;
updates             = update { "," update } [ "," ] ;
update              = IDENT "=" expression ;
xor_expr            = or_expr { "^" or_expr } ;
or_expr             = and_expr { "||" and_expr } ;
and_expr            = comparison_expr { "&&" comparison_expr } ;
comparison_expr     = sum_expr [ comparison_op sum_expr ] ;
comparison_op       = "==" | "!=" | "<" | "<=" | ">" | ">=" | ".." ;
sum_expr            = product_expr { ( "+" | "-" ) product_expr } ;
product_expr        = power_expr { ( "*" | "/" | "%" ) power_expr } ;
power_expr          = prefix_expr [ "**" power_expr ] ;
prefix_expr         = ( "+" | "-" | "!" ) prefix_expr | postfix_expr ;
postfix_expr        = primary_expr { call_suffix | index_suffix | field_suffix } ;
call_suffix         = "(" [ expressions ] ")" ;
index_suffix        = "[" expression "]" ;
field_suffix        = "." IDENT ;
expressions         = expression { "," expression } [ "," ] ;
primary_expr        = scalar_literal | value_reference | lambda_expr | case_expr
                    | list_expr | "(" expression ")" | tuple_expr ;
scalar_literal      = INT_LIT | FLOAT_LIT | DOUBLE_LIT | CHAR_LIT | STRING_LIT
                    | "true" | "false" | "(" ")" ;
value_name          = IDENT [ "::" IDENT ] ;
value_reference     = value_name [ type_arguments ] | variant_reference ;
variant_reference   = type_name [ type_arguments ] "::" IDENT ;
list_expr           = "[" [ expressions ] "]" ;
tuple_expr          = "(" expression "," [ expressions ] ")" ;
lambda_expr         = "lambda" "(" [ lambda_parameters ] ")"
                      [ ":" type_expr ] block ;
lambda_parameters   = lambda_parameter { "," lambda_parameter } [ "," ] ;
lambda_parameter    = IDENT [ ":" type_expr ] ;

case_expr           = "case" expression "{" [ branches ] "}" ;
branches            = branch { "," branch } [ "," ] ;
branch              = pattern [ "if" expression ] "=>" ( block | expression ) ;
pattern             = "_" | scalar_literal | negative_literal | IDENT
                    | constructor_pattern | struct_pattern | type_pattern
                    | "(" pattern ")" | tuple_pattern | list_pattern ;
negative_literal    = "-" ( INT_LIT | FLOAT_LIT | DOUBLE_LIT ) ;
constructor_pattern = variant_reference [ "(" patterns ")" ] ;
struct_pattern      = type_name [ type_arguments ] "{" [ field_patterns ] "}" ;
field_patterns      = field_pattern { "," field_pattern } [ "," ] ;
field_pattern       = IDENT ":" pattern ;
type_pattern        = type_expr IDENT ;
patterns            = pattern { "," pattern } [ "," ] ;
tuple_pattern       = "(" pattern "," [ patterns ] ")" ;
list_pattern        = "[" [ rest_pattern | list_pattern_items ] "]" ;
list_pattern_items  = pattern { "," pattern } [ "," [ rest_pattern ] ] ;
rest_pattern        = ".." ( IDENT | "_" ) [ "," ] ;
```

## 24. Implementation requirements and language boundaries

### 24.1. Representation and optimization

JVM bytecode and Java generation MUST obey the same program semantics. Primitive
boxing, erased or specialized generic methods, shared ADT classes or per-variant
classes are implementation choices. Dynamic checks use semantic types, not merely
physical object classes.

Optimization MUST preserve values, normal/abnormal completion, and observable
effect order. It cannot duplicate a subject/guard/`with` base, reorder callbacks,
replace snapshot capture with a shared mutable cell, widen numbers inside existing
containers, or alter ordinary arithmetic rounding. Effectful calls cannot be
removed merely because their result is discarded. Platform resource limits do
not guarantee a particular allocation count or mandatory tail-call optimization.

### 24.2. Excluded constructs

Core 1.0 has no classes/inheritance, user methods or operator overloading, open
sums, OR/as-patterns, GADTs, existential/
dependent/higher-kinded types, user variance or traits, rank-N polymorphism,
mutable fields/collections, Map/Set, nullable source types, `try`/`catch`/`throw`,
automatic `?`, `break`/`continue`, import aliases, macros, effect systems, ownership,
or borrow checking. Their absence does not relax required functional APIs or ADTs.

### 24.3. Conformance checking

An implementation test set must cover lexical boundaries and malformed literals;
scopes and exports; numeric/union conversions; invariance; universal generic checking
and polymorphic recursion; evaluation order; early returns; snapshot captures;
immutability and foreign aliasing; every pattern form, guards, exhaustiveness,
unreachability, and uninhabited types; structural equality with NaN/signed zero;
module initialization and the native boundary.

Examples and tutorials do not replace normative rules. A discrepancy between the
current compiler and this document is recorded in the
[implementation status](implementation-status.md), rather than becoming an implicit
exception to the specification.
