# Output Log Tool — Too Many Images Issue

## Problem

When querying the REPL output log, the tool returns excessive image data (base64-encoded images from evaluation results). This bloats responses and can overwhelm agent context windows.

## Observed During

Attempting to verify `calva.loadFile` command behavior via REPL evaluation — the output log results included large image payloads from prior evaluations.

## Impact

- Agent context window pollution
- Slow responses due to large payloads
- Makes the output log tool impractical for checking recent evaluation results

## Needs Investigation

- What controls image inclusion in output log queries?
- Should there be a filter/limit on image content in responses?
- Should images be excluded by default with an opt-in parameter?
