# Fleet operations

Ships live in two registries: the Maritime Registry (current) and the legacy Dockyard.
A ship missing from one may still exist in the other.

## Find a ship

1. If you have an IMO number, call `get-ship`.
2. If `get-ship` returns no match, call `list-legacy-vessels` and look for the IMO number
   there before telling the user the ship does not exist.
3. If you only have a name or a status, call `list-ships` (and `list-legacy-vessels` for
   older vessels), then confirm the right ship with the user before going further.

## Inspect a ship

- Call `get-ship-with-crew` when the user asks who is on board: it returns the ship with
  crew names already resolved.
- Say which registry each ship came from. Legacy vessels can be `laid_up` or `scrapped`;
  never treat them as available for a voyage.

## Errors

If a tool returns an error, report it with the IMO number you used. Do not retry with a
guessed IMO number.
