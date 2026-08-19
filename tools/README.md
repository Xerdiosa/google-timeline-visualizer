# Safe-sharing data indexes

Safe sharing uses a compact offline city index containing a stable ID, display
name, country code, and coordinate so users can select private areas.

Regenerate the indexes with:

```bash
curl -LO https://download.geonames.org/export/dump/cities15000.zip
python3 tools/generate_city_index.py cities15000.zip app/src/main/assets/city_centers.csv
```

GeoNames data is licensed under CC BY 4.0; attribution is included in the app
privacy policy and project README.
