# Output Log Query Reference

The `clojure_repl_output_log` tool queries a Datascript database of REPL output messages. This reference covers query patterns beyond the basics in the skill.

## Entity Shape

Each message has these attributes:

| Attribute | Type | Notes |
|---|---|---|
| `:output/line` | integer | Monotonic sequence number |
| `:output/category` | string | See categories below |
| `:output/text` | string | Message content |
| `:output/who` | string | Evaluator slug — absent on some system messages |
| `:output/timestamp` | integer | Epoch milliseconds |

**Categories:** `"evaluationResults"`, `"clojureCode"`, `"evaluationOutput"`, `"evaluationErrorOutput"`, `"otherOutput"`, `"otherErrorOutput"`

## Tool Interface

- `query` (required): Datalog query as an EDN string
- `inputs` (optional): JSON array of values for `:in` clause parameters after `$`

The database (`$`) is passed automatically as the first `:in` argument. Use `pull` to select only the attributes you need.

## Query Patterns

### Get your checkpoint (latest line number)

```
query:   [:find (max ?l) . :where [?e :output/line ?l]]
```

Use this at the start of a task to establish a baseline. Then pass it as `?since` in subsequent queries.

### Errors since checkpoint

```
query:   [:find [(pull ?e [:output/line :output/text :output/who]) ...]
          :in $ ?since
          :where [?e :output/category "evaluationErrorOutput"]
                 [?e :output/line ?l]
                 [(> ?l ?since)]]
inputs:  [42]
```

### Your own evaluations since checkpoint

```
query:   [:find [(pull ?e [:output/line :output/category :output/text]) ...]
          :in $ ?who ?since
          :where [?e :output/who ?who]
                 [?e :output/line ?l]
                 [(> ?l ?since)]]
inputs:  ["coder", 100]
```

### Another agent's output

```
query:   [:find [(pull ?e [:output/line :output/category :output/text :output/who]) ...]
          :in $ ?who
          :where [?e :output/who ?who]]
inputs:  ["reviewer"]
```

### Activity overview (lightweight scan)

```
query:   [:find [(pull ?e [:output/line :output/category :output/who]) ...]
          :where [?e :output/line]]
```

Omits `:output/text` to keep the result compact. Useful for getting a sense of what's happened without pulling message bodies.

### Count by category

```
query:   [:find ?cat (count ?e)
          :where [?e :output/category ?cat]]
```

Quick health check — see how many errors, results, stdout messages exist.

### Time-windowed queries

For long sessions, filter by timestamp instead of line number. Compute the cutoff epoch-ms and pass it as input:

```
query:   [:find [(pull ?e [:output/line :output/category :output/text :output/who]) ...]
          :in $ ?since-ts
          :where [?e :output/timestamp ?ts]
                 [(> ?ts ?since-ts)]]
inputs:  [1711900000000]
```

To query "last 5 minutes", compute `Date.now() - 300000` and pass that value.

### Errors from a specific evaluator

Combine category and who filters:

```
query:   [:find [(pull ?e [:output/line :output/text]) ...]
          :in $ ?who
          :where [?e :output/category "evaluationErrorOutput"]
                 [?e :output/who ?who]]
inputs:  ["coder"]
```

### Text search with regex

Use `re-find` in a predicate clause. Supports `(?i)` for case-insensitive matching:

```
query:   [:find [(pull ?e [*]) ...]
          :where [?e :output/text ?t]
                 [(re-find #"(?i)exception" ?t)]]
```

`clojure.string/includes?` also works for exact substring matches but `re-find` is more versatile.

### Text search with truncated previews

Scan output without pulling full message bodies into your context window:

```
query:   [:find ?l ?cat ?snippet
          :where [?e :output/text ?t]
                 [(re-find #"(?i)error" ?t)]
                 [?e :output/line ?l]
                 [?e :output/category ?cat]
                 [(count ?t) ?len]
                 [(min ?len 50) ?end]
                 [(subs ?t 0 ?end) ?snippet]]
```

The `count`/`min`/`subs` chain safely truncates: short text stays as-is, long text is cut at 50 characters. Adjust the limit as needed. Use this to locate relevant lines, then fetch full entities for the ones you care about.

## Tips

- **Checkpoint pattern**: Query `(max ?l)` at task start, then use it as `?since` in all subsequent queries. This gives you incremental reads without re-fetching old data.
- **Use `pull` selectively**: `:output/text` can be large. Omit it when scanning for structure, include it when you need content.
- **The `who` field is optional on messages**: System messages (connection notifications, jack-in output) often lack `:output/who`. Queries filtering on `:output/who` will exclude these. If you need everything, filter on `:output/line` or `:output/category` instead.
- **Results are unordered**: Datascript returns sets. If order matters, sort by `:output/line` after receiving results.

## Available Predicate Functions

Datascript whitelists a specific set of functions for use in predicate clauses (`[(fn ?arg) ?result]`). These are the only functions that work — others will throw "Unknown function":

**Arithmetic:** `+` `-` `*` `/` `mod` `quot` `rem` `inc` `dec`

**Comparison:** `=` `==` `!=` `not=` `<` `<=` `>` `>=` `compare` `identical?`

**Predicates:** `nil?` `true?` `false?` `some?` `zero?` `pos?` `neg?` `even?` `odd?` `empty?` `not-empty` `contains?`

**Strings:** `str` `subs` `name` `namespace` `pr-str` `print-str` `re-find` `re-matches` `re-pattern` `re-seq` `clojure.string/blank?` `clojure.string/includes?` `clojure.string/starts-with?` `clojure.string/ends-with?`

**Collections:** `get` `count` `vector` `list` `set` `hash-map` `array-map` `range`

**Logic:** `and` `or` `not` `complement` `identity`

**Datascript-specific:** `get-else` `get-some` `ground` `missing?` `tuple` `untuple` `-differ?`

**Other:** `type` `meta` `keyword` `rand` `rand-int` `min` `max`

Notable absences: `clojure.string/split`, `clojure.string/lower-case`, `clojure.string/upper-case`, and all higher-order functions (`map`, `filter`, `reduce`). For case-insensitive matching, use `re-find` with the `(?i)` flag.
