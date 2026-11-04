"""Writes the static ship registry nginx serves: /ships (20 ships), /fleet (1,000) and /ships/<imo>."""
import json
import pathlib
import sys

out = pathlib.Path(sys.argv[1])
(out / "ships").mkdir(parents=True, exist_ok=True)
statuses = ["active", "docked", "maintenance", "decommissioned"]


def ship(n):
    return {
        "imo_number": f"IMO-{9000000 + n}",
        "vessel_name": f"Vessel {n:04d}",
        "vessel_type": "container" if n % 3 else "tanker",
        "flag_code": ["FR", "MA", "NO", "PA"][n % 4],
        "operational_status": statuses[n % 4],
        "year_built": 1990 + n % 35,
        "gross_tonnage": 20000 + 37 * n,
        "dimensions": {"length_overall": 150 + n % 250, "beam": 20 + n % 40},
        "owner": {"name": f"Owner {n % 50}", "country": "FR"},
    }


fleet = [ship(n) for n in range(1, 1001)]
(out / "fleet.json").write_text(json.dumps(fleet))
(out / "ships.json").write_text(json.dumps(fleet[:20]))
for s in fleet[:20]:
    (out / "ships" / f"{s['imo_number']}.json").write_text(json.dumps(s))
