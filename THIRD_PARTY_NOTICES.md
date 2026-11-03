# Third party source notices

## PiuPiu 2.7.9 compatibility modules

The unmodified `app/src/main/assets/card-modules/openai.js` and `utils.js` were
recovered from the user's PiuPiu Android client package (APK SHA-256
`258e85bf0f18347d13c59e9907c79c1728ef5cc0eaaa272057d835741c9d319a`).
Their respective SHA-256 values are
`c0882664c0088e0ae91acf64a3c6eb7b91eaa769bc4c321c6a9938a5d72ba86a`
and `5a0f861975bdafcca6c9e184e57ec6720b13d8c85df54681da72c5a5ebdd4e54`.
Their original package did not identify a separate license for these two files.
The host adapter is implemented in Xujing's `card-runtime.js` and `CardMessage.kt`.

`card-modules/world-info.js` and `preset-manager.js` are also unmodified
recovered files from the same package, with respective SHA-256 values
`03170c4dfb404c38eb0656f3156a48fbb85628db5d40b721b3e5dc6d559967c7` and
`842565ab9fc6288c0411a5c33bc5faea7cf082633d2cf8e81a08f60e903bdde2`.
The source package did not identify a separate license for these files.

`card-modules/script.js` is the recovered root module from the same package;
its SHA-256 is `ae901307749ebf90c5a550c791b75be25c168f5dee04c5ee0686d6091d870a9d`.

## Recovered card runtime libraries

`app/src/main/assets/card-vendors/` contains EJS 3.1.10 (Apache-2.0),
Zod 4.4.3 (MIT), and selected PiuPiu MVU helpers/schema hooks from the same APK.
The extraction input, hashes and scope are recorded in `provenance.json`.
`tools/extract-card-vendors.cjs` decodes library string literals and selected
declarations without executing the application chunk. The EJS engine uses an
Android browser IO adapter; file-based includes require an explicit includer.
The MVU fragment uses Xujing variable/event adapters; parseMessage and reloadInitVar
remain unsupported. No separate license was identified for PiuPiu's own wrapper.
`card-template.js` adapts the recovered template-wrapper behavior.

License copies are in `licenses/` and APK `assets/licenses/`.
Zod license source: https://raw.githubusercontent.com/colinhacks/zod/v4.4.3/LICENSE

## Material icons

Only the Material icons referenced by this application are included as source under
`app/src/main/java/androidx/compose/material/icons/`. The original copyright and
Apache License 2.0 headers are retained in each file. Basic icons are provided by
the AndroidX `material-icons-core` dependency.

Source artifact: Google Maven,
`androidx.compose.material:material-icons-extended-android:1.7.8:sources`.

Upstream SHA-1: `fd5e20a9a49831a8c9ce04b7012d1ea5ff755a7c`.

The selected files can be refreshed with `tools/extract-used-icons.ps1`. The full
extended icon library is not needed at runtime. No model weights are included.

License: [Apache License 2.0](licenses/Apache-2.0.txt), also included in the APK
under `assets/licenses/Material-icons-Apache-2.0.txt`.
