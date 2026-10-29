# Voyage operations

`create-voyage` books a ship and its crew, so check before you call it.

1. Confirm the ship exists and is active (see the `fleet-ops` skill). Legacy vessels that are
   `laid_up` or `scrapped` cannot sail.
2. Read back the ship, ports, dates and crew to the user and wait for an explicit yes.
3. Dates are ISO-8601, and arrival must be after departure.
4. After `create-voyage` succeeds, give the user the voyage identifier it returns.

If `create-voyage` fails, show the error and ask the user what to change. Do not resubmit the
same request.
