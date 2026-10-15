# Claude Code ADT

Fork von *Claude Code for Eclipse IDE* (eilonwy06, Stand 3.2.19) mit Werkzeugen für
SAP ABAP Development Tools (ADT). Das Plugin läuft parallel zum Original-Plugin.

## Bauen und installieren (lokal, ohne Maven und ohne Netzwerk)

```powershell
.\build-local.ps1 -Install          # baut und kopiert nach C:\ADT\eclipse\dropins
.\build-local.ps1 -EclipseHome D:\eclipse -Install
```

Danach muss Eclipse neu gestartet werden. Taucht das Plugin nicht auf, startest du Eclipse
einmal mit `-clean`.

Das Skript kompiliert mit dem JDK, das Eclipse mitbringt (JustJ), gegen alle Plugins der
Installation. Die Bundle-Version bekommt den Build-Zeitpunkt als Qualifier, so ist jeder
Build neuer als der vorige. Die Jar liegt danach unter `build-local\`.

Zum **Deinstallieren** löschst du `dropins\com.anthropic.claudecode.eclipse.adt_*.jar` und
startest Eclipse neu.

### Verteilung auf andere Rechner (offline, ohne Admin-Rechte)

```powershell
.\build-local.ps1 -UpdateSite      # erzeugt build-local\ClaudeCodeADT-<version>.zip
```

Die ZIP-Datei ist eine p2-Update-Site. Sie enthält das Feature, das Plugin sowie die
Terminal-Bundles und Gson, falls sie im Ziel-Eclipse fehlen. So installiert man sie, auch in
Azure Virtual Desktop ohne Zugriff auf das Eclipse-Verzeichnis:

1. *Help > Install New Software… > Add… > Archive…* und die ZIP-Datei wählen.
2. „Claude Code ADT“ anhaken, *Next*, Lizenz bestätigen.
3. Die Frage nach **nicht signierten Inhalten** bestätigen (*Install anyway* bzw. Trust). Das
   Plugin ist nicht signiert.
4. Eclipse neu starten.

Ist die Installation schreibgeschützt, installiert p2 in den Benutzerbereich
(`%USERPROFILE%\.eclipse\…`). Netzwerk wird dafür nicht gebraucht. Ist die Abfrage für
fehlende Abhängigkeiten zu langsam, nimmt man im Install-Dialog den Haken bei *Contact all
update sites during install to find required software* heraus.

Updates installiert man genauso mit der neueren ZIP-Datei, p2 ersetzt dann die alte Version.
Deinstalliert wird über *Help > About Eclipse IDE > Installation Details*.

Ziel-Eclipse: Java 21 oder neuer und eine Eclipse-Version mit den Terminal-Bundles
`org.eclipse.terminal.*` 1.x. Gebaut wird gegen die lokale Installation (hier Eclipse 4.41).
**Nicht** gleichzeitig über `dropins` und die Update-Site installieren: Beides ist dasselbe
Singleton-Bundle.

### Tycho-Build (mit Maven und Netzwerk)

```powershell
.\build-tycho.ps1
```

Das ist der Build wie in der Upstream-CI: Maven **3.9.16**, denn 3.10 scheitert an
`tycho-surefire`, dazu ein JDK ab 21 und Zugriff auf Maven Central und die p2-Repositories
aus `claudecode.target`. Pfade für Maven und JDK lassen sich mit `-MavenHome` und
`-JavaHome` ändern. Ergebnis ist unter anderem
`com.anthropic.claudecode.eclipse.site\target\com.anthropic.claudecode.eclipse.adt.site-<version>.zip`.
Diese Update-Site enthält nur Feature und Plugin. Für Rechner ohne Netz, denen
Terminal-Bundles fehlen könnten, ist die ZIP aus `build-local.ps1 -UpdateSite` die
sicherere Wahl.

## Was sich gegenüber dem Original unterscheidet

| | Original | Claude Code ADT |
|---|---|---|
| Bundle-SymbolicName | `com.anthropic.claudecode.eclipse` | `com.anthropic.claudecode.eclipse.adt` |
| Feature | `com.anthropic.claudecode.eclipse.feature` | `com.anthropic.claudecode.eclipse.adt.feature` |
| IDs (Views, Commands, Kontexte, Einstellungen …) | `com.anthropic.claudecode.eclipse.*` | `com.anthropic.claudecode.eclipse.adt.*` |
| Views | Claude Code, Claude Terminal | Claude Code (ADT), Claude Terminal (ADT) |
| Menü / Einstellungsseite | Claude Code | Claude Code ADT |
| Native Bibliothek | `claude_eclipse_core.dll` | `claude_eclipse_adt_core.dll` (gleiche Binärdatei, umbenannt) |
| IDE-Name in `/ide` | Eclipse | Eclipse (ADT) |
| Globale Tastenkürzel | Strg+Shift+C, Strg+Alt+S, Strg+Alt+A, Strg+` | keine (würden mit dem Original kollidieren; unter *Preferences > General > Keys* belegbar) |
| Exportierte Pakete | ja | keine |

**Die Java-Pakete sind unverändert** (`com.anthropic.claudecode.eclipse.*`). Die vorkompilierte
native Bibliothek ist per JNI an `com.anthropic.claudecode.eclipse.NativeCore` gebunden, und
jedes Bundle lädt seine Klassen über einen eigenen Classloader. Deshalb stören sich die
gleichnamigen Pakete beider Plugins nicht.

Einstellungen und Zustand (Sitzungen, Lesezeichen, Vertrauensliste) liegen pro Bundle
getrennt. Die Einstellungen des Originals werden **nicht** übernommen.

## Neue und reparierte MCP-Tools

| Tool | Zweck |
|---|---|
| `readEditor` | Liest den Text eines Editors inklusive ungespeicherter Änderungen, auch für ABAP-Quellen auf `semanticfs:`. Optional mit Zeilenbereich, Zeilennummern und Tab (`page`) bei Klassen. Eine nicht geöffnete Datei wird ohne Editor aus dem Workspace gelesen. |
| `editEditor` | Ändert den Text eines Editors wie eine Tastatureingabe: Der Editor wird erst um Erlaubnis gefragt (bei ADT sperrt das das Objekt, ggf. mit Transportauftrags-Dialog), dann wird alles als ein Undo-Schritt eingespielt. Arbeitet mit `edits` (`old_string`/`new_string`/`replace_all`, wie das Edit-Tool) oder `new_content`. Speichert nur mit `save: true`. |
| `adtCommand` | Führt ADT-Befehle im Editor aus: `activate` (Strg+F3), `checkSyntax` (Strg+F2), `prettyPrint` (Shift+F1). Wartet bei Aktivieren und Prüfen auf ADT und liefert die Probleme der Datei zurück. Wird nur angeboten, wenn ADT installiert ist. |
| `getDiagnostics` | Findet jetzt auch `semanticfs:`-Dateien und liefert bei offenen Editoren deren Annotationen (z. B. ABAP-Syntaxfehler). |
| `saveDocument`, `checkDocumentDirty`, `openFile` | Kamen mit `semanticfs:`-Pfaden nicht zurecht (`Path.of` scheitert am Doppelpunkt); finden Editoren jetzt wie die neuen Tools. |

Editoren lassen sich auf mehrere Arten benennen: über den Pfad aus `getOpenEditors`
(`semanticfs:/…`), den Workspace-Pfad (`/Projekt/…`), einen Dateipfad auf der Platte oder den
Editor-Titel (`ZCL_FOO`, `[E10] ZCL_FOO`). Ohne Angabe wird der aktive Editor verwendet.

Ein Dialog, den ein Tool öffnet (z. B. die Frage nach dem Transportauftrag), wird wie im
Original an Claude gemeldet und kann mit `eclipseDialog` beantwortet werden.

## Native Bibliotheken

Die Bibliotheken unter `native/`, die mit dem Quellcode eingecheckt waren, waren veraltet.
Die Windows-DLL exportierte 79 der 96 JNI-Funktionen, die `NativeCore.java` deklariert, und
erwartete bei `chatSendMessage` andere Argumente. Das Original baut sie erst beim Release in
der CI (`deploy.yml`). Mit der alten DLL stürzte die JVM beim ersten Prompt in der
Claude-Code-View ohne Meldung ab (`hs_err_pid*.log`: `EXCEPTION_ACCESS_VIOLATION` in
`chatSendMessage`).

Ersetzt wurden sie durch die Bibliotheken aus dem Release-Jar
`com.anthropic.claudecode.eclipse_3.2.18.202610140510.jar`, für alle Plattformen und
umbenannt in `claude_eclipse_adt_core`. Alle 96 native Methoden haben in 3.2.18 und 3.2.19
dieselben Signaturen.

`build-local.ps1` prüft vor dem Packen, ob die Windows-DLLs alle native Methoden aus
`NativeCore.java` exportieren, und bricht sonst ab. Bei einem Update auf eine neuere
Upstream-Version müssen die Bibliotheken aus dem passenden Release-Jar übernommen oder mit
`cargo` neu gebaut werden.

## Hinweise zu ADT

- **Anmeldung:** Nach einem Neustart zeigen ADT-Editoren nur einen Platzhalter, bis man sich
  am SAP-System angemeldet hat. Die Anmeldung startet erst ein Klick in den Editor, ein
  Aktivieren per Tool genügt nicht. Bis dahin melden die Editor-Tools „is not a text editor“
  mit dem Hinweis, dass die Anmeldung vermutlich fehlt. Danach funktionieren sie ohne
  weiteres Zutun.
- **`adtCommand` wartet** nur auf die Jobs, die der ADT-Befehl selbst startet. Aktivieren und
  Syntaxprüfung antworten nach etwa 1,5 Sekunden. Die Fehler der Prüfung stehen unter
  `problems` mit Zeile und Meldung (Markertyp `com.sap.adt.tools.core.abapSyntaxCheckProblem`).
- **Transportaufträge wählt der Nutzer:** Öffnet ADT bei `editEditor`, `saveDocument` oder
  `adtCommand` einen Dialog, etwa „Select a Transport Request“, beantwortet ihn der Nutzer.
  Der Tool-Aufruf wartet so lange und meldet dann das Ergebnis: geändert bzw. gespeichert
  bzw. aktiviert, oder abgebrochen. Er kehrt nicht vorher mit `dialogOpened` zurück.
  Transport-Dialoge sind „nur für den Nutzer“ markiert. `eclipseDialog` zeigt sie an, kann
  darin aber nichts auswählen, eintippen oder drücken.
- **Andere Dialoge:** `eclipseDialog` liest mit `list` auch Tabellen, Bäume, Listen und
  Eingabefelder. Mit `select` wählt es eine Zeile oder Option, mit `type` füllt es ein Feld,
  und mit `press` drückt es einen Knopf.
  - Ein offener modaler Dialog sperrt das Eclipse-Hauptfenster und damit die Freigabe-Karte
    in der View „Claude Code (ADT)“. Die Freigabe erscheint dann als eigener Dialog über dem
    offenen Dialog. Er ist „nur für den Nutzer“ markiert, `eclipseDialog` kann ihn also nicht
    drücken.
  - `eclipseDialog` mit `list` ist rein lesend und braucht keine Freigabe.
  - In der **Claude Terminal (ADT)**-View steht die Freigabe im Terminal selbst, das ein
    modaler Dialog ebenfalls sperrt. Dort hilft nur, `mcp__eclipse__eclipseDialog` in den
    Claude-Einstellungen dauerhaft zu erlauben, oder man arbeitet in der View „Claude Code
    (ADT)“.
- **`adtCommand activate`** meldet `activated` sowie `cancelled: true`, wenn der Editor danach
  noch ungespeicherte Änderungen hat. Dann wurde weder gespeichert noch aktiviert, meist weil
  ein Dialog abgebrochen wurde.
- **SAP ADT hat einen eigenen MCP-Server** (`com.sap.adt.mcp.core`) mit 20 Tools, unter
  `http://localhost:2234/mcp` (siehe Eclipse-Log).

## Bekannte Grenzen

- **Native Bibliothek nicht neu gebaut:** Ein paar Namen sind in Rust fest eingebaut und gelten
  daher auch im Fork. Die Claude-Code-View meldet sich der CLI als „Eclipse“, und der
  MCP-Server heißt in beiden Views `eclipse`, die Tools also `mcp__eclipse__*`. In der
  Lock-Datei überschreibt Java den Namen nachträglich mit „Eclipse (ADT)“. `Cargo.toml` erzeugt
  bei einem Neubau bereits `claude_eclipse_adt_core`.
- **`TerminalScrollbar`** braucht die GTK-Interna von SWT. Unter Windows kompiliert
  `build-local.ps1` die Klasse mit ecj und `-proceedOnError`. Die GTK-Methoden sind danach
  Stubs, die unter Windows nie aufgerufen werden.
- Die Resolver für C/C++ (CDT) und Python (PyDev) werden nur gebaut, wenn diese Plugins
  installiert sind.
- Die GitHub-Workflows unter `.github/` sind nicht an den Fork angepasst.
- Die Tests (`test/`) laufen nur im Tycho-Build und wurden für den Fork nicht ausgeführt.
