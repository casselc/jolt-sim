# Draft metaschemas

The JSON Schema metaschemas for drafts 4, 6 and 7, from
[jsonschema-specifications](https://github.com/python-jsonschema/jsonschema-specifications)
2025.9.1 (MIT; see `LICENSE`), unmodified. Schemas that reference their own
metaschema (`"$ref": "http://json-schema.org/draft-07/schema#"`) import with
these as further documents. The 2019-09 and 2020-12 metaschemas are not
here: they use `$dynamicRef` and vocabularies, which the importer refuses.
