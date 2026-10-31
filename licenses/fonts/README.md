# Bundled typefaces — where the license texts live

Every family's license text is bundled inside the APK directly beside the font files it covers:

    app/src/main/assets/fonts/<category>/<family>/OFL.txt                  (SIL Open Font License 1.1)
    app/src/main/assets/fonts/<category>/<family>/LICENSE-Apache-2.0.txt    (Apache License 2.0)

[FONTS.md](FONTS.md) is the per-file inventory: family, style, bundled path, license, and the exact
`github.com/google/fonts` source URL for each of the 185 files. All files come from the Google Fonts
repository (`ofl/`, plus five families from `apache/`), commit `6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec`.
No font in this app comes from DepthX, from any APK, or from any other app.
