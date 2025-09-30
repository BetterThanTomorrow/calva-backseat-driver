# Backseat Driver Test Project

This project is a testing environment for development versions of **Backseat Driver**, a Model Context Protocol (MCP) server that provides Clojure-focused development tools for AI agents.

## Purpose

Test and validate Backseat Driver's toolset:
- Structural editing tools for Clojure files
- REPL integration and evaluation
- Symbol documentation and ClojureDocs lookup
- Bracket balancing and code utilities
- Human intelligence integration

## For AI Agents Working in This Project

You have access to Backseat Driver's Clojure-focused MCP tools. Use them to follow **interactive programming** (REPL-driven development) practices:

### Interactive Programming Principles

1. **Start small and build incrementally** - Begin with simple expressions, build up complexity
2. **Validate each step through REPL evaluation** - Test every piece of code as you develop it
3. **Use rich comment blocks for experimentation** - Develop in `(comment ...)` forms before moving to production code
4. **Let REPL feedback guide the design** - The REPL holds the truth
5. **Prefer composable, functional transformations** - Think data-first, functional approaches

### Development Workflow

**When editing Clojure files:**
1. Use structural editing tools (**Create Clojure File**, **Append Code**, **Insert Top Level Form**, **Replace Top Level Form**)
2. Develop solutions in the REPL before applying file edits
3. Evaluate code incrementally - test subexpressions rather than using `println`
4. Reload namespaces after file changes with **Evaluate Clojure Code** tool
5. Verify diagnostics after each structural edit

**When exploring APIs:**
- Use **Clojure Symbol Info** tool for REPL-connected documentation
- Use **ClojureDocs Info** tool for community examples and patterns
- Follow "see also" links to discover related functions

**When debugging:**
- Evaluate subexpressions to understand behavior
- Use inline `def` debugging to capture values (in REPL evaluation, not in files)
- Query **REPL Output Log** to see application state

### Testing Backseat Driver

When testing tool updates:
- Create dedicated test files (e.g., `test_round_<n>.clj`)
- Test complete workflows: create → append → insert → replace → evaluate
- Verify both success paths and error handling
- Check that diagnostics are meaningful

See `.github/instructions/testing-tools.instructions.md` for comprehensive testing strategies.

## Project Structure

```
src/mini/          - Test Clojure files
test/mini/         - Test namespace (future use)
.github/           - Instructions and memory files
```

## Resources

- [Backseat Driver Documentation](https://github.com/BetterThanTomorrow/calva-backseat-driver)
- [Interactive Programming Guide](AI_INTERACTIVE_PROGRAMMING.md)
- [Testing Strategies](.github/instructions/testing-tools.instructions.md)
