#!/usr/bin/env python3
"""Build the compact in-app city index from a GeoNames cities ZIP archive."""

import argparse
import csv
import io
from pathlib import Path
from urllib.parse import quote
import zipfile


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path, help="GeoNames cities15000.zip")
    parser.add_argument("output", type=Path, help="Generated compact CSV")
    args = parser.parse_args()

    with zipfile.ZipFile(args.input) as archive:
        data_file = next(name for name in archive.namelist() if name.endswith(".txt"))
        with archive.open(data_file) as raw:
            rows = csv.reader(io.TextIOWrapper(raw, encoding="utf-8"), delimiter="\t")
            cities = [
                (row[0], float(row[4]), float(row[5]), row[8], row[2])
                for row in rows
            ]

    cities.sort(key=lambda city: int(city[0]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="ascii", newline="\n") as output:
        output.write("# GeoNames cities15000; CC BY 4.0\n")
        output.write("# geoname_id,latitude,longitude,country_code,encoded_name\n")
        for geoname_id, latitude, longitude, country_code, name in cities:
            output.write(
                f"{geoname_id},{latitude:.6f},{longitude:.6f},"
                f"{country_code},{quote(name, safe='')}\n"
            )


if __name__ == "__main__":
    main()
