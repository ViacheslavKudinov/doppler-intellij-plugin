import os
import sys

mode = sys.argv[1] if len(sys.argv) > 1 else "plain"
if len(sys.argv) > 2 or mode not in ("plain", "custom"):
    print("FAIL: use plain or custom mode.", file=sys.stderr)
    sys.exit(2)

custom = mode == "custom"
expected = (
    {"APP_MESSAGE": "doppler-example", "APP_NUMBER": "42"}
    if custom
    else {"EXAMPLE_MESSAGE": "doppler-example", "EXAMPLE_NUMBER": "42"}
)
values_match = all(os.environ.get(name) == value for name, value in expected.items())
original_names_absent = not custom or all(
    name not in os.environ for name in ("EXAMPLE_MESSAGE", "EXAMPLE_NUMBER")
)
if not values_match or not original_names_absent:
    print(
        "FAIL: example variables are missing, unexpected, or still present under their original names.",
        file=sys.stderr,
    )
    sys.exit(1)

print("PASS: both Doppler example variables reached Python.")