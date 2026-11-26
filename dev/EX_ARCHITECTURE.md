# Calva Backseat Driver - The Loop

Backseat Driver is built around a unidirectional action/effect loop, following the Uniflow idea: actions decide, effects execute, and the loop is the only writer of the state. For the idea itself, load the Uniflow skill (`uniflow`). [Replicant TodoMVC](https://github.com/anteoas/replicant-todomvc) is where Uniflow was invented. This page covers what is particular to Backseat Driver's loop.

The loop lives in the project source, in [`event_handler.cljs`](../src/calva_backseat_driver/event_handler.cljs), so it can evolve with the extension. vscode-mcp runs its server's lifecycle on a copy of it, `vscode-mcp.event-handler`, the same text apart from the namespace name, so a change to one goes to the other.

## Actions and effects

An action is a vector, `[action-name & args]`. Its handler is a named function:

```clojure
(defn- sessions-read
  [state _uf-data sessions]
  {:uf/db (assoc state :calva/sessions sessions)
   :uf/dxs [[:mcp/ax.update-registry]]})
```

`uf-data` is `{:system/now ms}`. A handler returns any of `:uf/db` (the next state), `:uf/fxs` (effects, performed in order) and `:uf/dxs` (actions dispatched after the effects).

An effect is a vector too, performed by `(effect dispatch! & args)`. It does the side effect, and may dispatch or return a value.

There is no enrichment. What an action needs is in the state (the settings under `:vscode/config.<name>`, the manifest under `:extension/manifest`), or arrives as an argument.

## The tables

Each domain namespace ends with a table: `(def actions {:calva/ax.add-output #'add-output …})` in `*/axs.cljs`, `(def effects {…})` in `*/fxs.cljs`. [`app/dispatch.cljs`](../src/calva_backseat_driver/app/dispatch.cljs) merges them and holds `dispatch!`. To see them:

```clojure
(keys (calva-backseat-driver.app.dispatch/actions))
(keys (calva-backseat-driver.app.dispatch/effects))
```

The tables hold vars, so a hot reload or a REPL evaluation of a handler takes effect at the next dispatch.

## Awaiting

An effect written as `[:uf/await :some/fx.name & args]` is awaited before the next one runs. Its result replaces `:uf/prev-result` where that is an element of a later effect vector or of a deferred action:

```clojure
{:uf/fxs [[:uf/await :calva/fx.read-sessions]]
 :uf/dxs [[:calva/ax.sessions-read :uf/prev-result]]}
```

`dispatch!` takes a vector of actions and returns a native promise of the chain's last awaited result. It never throws. `[:uf/await :app/fx.return v]` gives a dispatch its value, which is how a command, a tool call or an MCP request gets its answer from the loop. The loop uses native promises throughout.

Dispatching later is done with effects in `app/fxs.cljs`: `:uf/fx.dispatch`, `:uf/fx.defer-dispatch` (with a key; a later one with the same key replaces the one waiting, which is how the eval history's save is debounced) and `:uf/fx.cancel-deferred-dispatch`.

## When something is wrong

A name that is in no table, a handler result with an unknown key, an action or effect vector with the wrong number of arguments, and two awaiting actions in one batch each fail. They throw in a development build and are logged in a release build. The var `*development-build?*` in `event_handler.cljs` says which kind of build it is, and the loop's tests set it to test both.

`@calva-backseat-driver.app.dispatch/!dispatch-log` holds the last 200 dispatches of a development build: the actions, the effects, the deferred actions, and the state before and after.

## The state and what is beside it

The state in [`app/db.cljs`](../src/calva_backseat_driver/app/db.cljs) holds plain values only, so it prints. The output log and the eval history are long, so print it with a `*print-length*` (see the comment form there).

The extension context, the disposables and the timers are live objects, kept in one map beside the state by [`app/live_objects.cljs`](../src/calva_backseat_driver/app/live_objects.cljs). Only effects and entry points touch them.

The when-contexts are computed from the state by [`app/when_contexts.cljs`](../src/calva_backseat_driver/app/when_contexts.cljs). The loop's state-effects hook, wired in `app/dispatch.cljs`, sets them when a state change gives them other values.

## Testing

Handlers are pure, so a unit test calls one with a state and checks the map it returns. The unit tests load every namespace; `test/vscode_stub.js` stubs `vscode` with named exports.
