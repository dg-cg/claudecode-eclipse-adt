# AGENTS.md – Hinweise für Coding-Agents

Dieses Repository ist **Claude Code ADT**: ein Fork des inoffiziellen Plugins *Claude Code for
Eclipse IDE* (Upstream: `eilonwy06/claudecode-eclipse-ide`) mit Werkzeugen für SAP ABAP
Development Tools (ADT). Es läuft parallel zum Original-Plugin in derselben Eclipse.

`FORK-ADT.md` beschreibt für Nutzer, was der Fork ändert und wie man ihn baut und verteilt.
Diese Datei ist für dich als Agent: Regeln, Umgebung, Abläufe und die Fallstricke, auf die
wir bereits gestoßen sind. Lies beide, bevor du etwas änderst.

## Regeln

1. **Java-Pakete nicht umbenennen.** Sie bleiben `com.anthropic.claudecode.eclipse.*`. Die
   vorkompilierte Rust-Bibliothek ist über JNI-Symbolnamen
   (`Java_com_anthropic_claudecode_eclipse_NativeCore_*`) fest an
   `com.anthropic.claudecode.eclipse.NativeCore` gebunden. Ein anderes Paket führt zu einem
   `UnsatisfiedLinkError`.
2. **IDs tragen `.adt`.** Bundle `com.anthropic.claudecode.eclipse.adt`, Feature
   `….adt.feature`. Alle Contribution-IDs in `plugin.xml` beginnen mit
   `com.anthropic.claudecode.eclipse.adt.`, die `class=`-Attribute dagegen nicht (Regel 1).
   Neue IDs in Java-Strings müssen dazu passen. Ohne diese Trennung kollidiert der Fork mit
   dem Original.
3. **Keine globalen Tastenkürzel.** Dieselbe Tastenfolge in beiden Plugins deaktiviert beide.
   Kürzel nur in eigenen Kontexten (`….adt.contexts.*`).
4. **Native Bibliotheken nur aus passender Quelle.** `native/` muss genau die Methoden
   exportieren, die `NativeCore.java` deklariert, und das mit identischen Signaturen.
   `build-local.ps1` prüft die Exporte. Die Signaturen kann es nicht prüfen. Rust lässt sich
   hier nicht bauen (kein `cargo`). Neue native Methoden sind deshalb tabu, solange niemand
   die Bibliothek neu baut.
5. **Transportaufträge sind Sache des Nutzers.** Dialoge zur Transportauswahl sind „nur für
   den Nutzer“ (`Dialogs.asksForTransport`). Tools, die ABAP-Objekte sperren, speichern oder
   aktivieren, warten auf die Antwort (`ToolDialogWatch.EXEMPT`). Das nicht aufweichen.
6. **Nichts an `dev` ändern.** `dev` ist der unveränderte Upstream-Stand. Gearbeitet wird auf
   `adt` (siehe Git).
7. **Stil des Upstreams beibehalten:** Code-Kommentare und Javadoc auf Englisch, erklärend
   („warum“, nicht „was“). Tools nach dem Muster der vorhandenen bauen (siehe unten).
   Nutzer-Doku (`FORK-ADT.md`) ist auf Deutsch.

## Aufbau

| Pfad | Inhalt |
|---|---|
| `com.anthropic.claudecode.eclipse/` | Das Plugin (Ordnername wie Upstream, Bundle-ID mit `.adt`) |
| `…/src/…/tools/` | MCP-Tools; Registrierung in `mcp/McpToolRegistry.java` |
| `…/tools/EditorTargets.java` | Editor-Suche für alle Editor-Tools: Pfad aus `getOpenEditors` (`semanticfs:/…`), Workspace-Pfad, Plattenpfad oder Titel (`ZCL_FOO`, `[E10] ZCL_FOO`); mehrseitige Editoren (ABAP-Klassen) |
| `…/tools/ReadEditorTool.java`, `EditEditorTool.java`, `AdtCommandTool.java` | Die ADT-Tools des Forks |
| `…/tools/Dialogs.java`, `EclipseDialogTool.java`, `ToolDialogWatch.java` | Dialoge lesen und bedienen, „nur für den Nutzer“, Tool-Aufrufe hinter Dialogen |
| `…/ui/ClaudeGuiView.java` | View „Claude Code (ADT)“; Freigaben in `requestApproval` |
| `…/NativeCore.java` | JNI-Brücke zur Rust-Bibliothek (Regel 1 und 4) |
| `…/native/` | Vorkompilierte Bibliotheken `claude_eclipse_adt_core.*` (aus dem 3.2.18-Release-Jar) |
| `claude-eclipse-core/` | Rust-Quellen der nativen Bibliothek (hier nicht baubar) |
| `….feature/`, `….site/` | Feature und Update-Site für den Tycho-Build |
| `build-local.ps1` | Lokaler Build ohne Maven/Netzwerk; Installation; Offline-Update-Site |
| `scripts/mcp-probe.ps1` | Ruft ein Tool des laufenden Plugins direkt auf (zum Testen) |
| `scripts/install-on-exit.ps1` | Installiert den neuesten Build in `dropins`, sobald Eclipse beendet ist |

Ein Tool implementiert `mcp/McpTool` (`toolName`, `description`, `inputSchema`, `execute`).
Für Parameter und Schemata nimmt man `ToolSupport` (`prop`, `schema`, `str`, `bool`,
`integer`). UI-Zugriffe laufen über `UiHelper.syncCall`, Fehler werden als
`McpToolResult.error` gemeldet. Die Tool-Liste liefert Java an den nativen Server, ein neues
Tool braucht also keinen Rust-Neubau. Die `description` ist das, was Claude über das Tool
weiß. Formuliere sie so, dass das Modell das Tool richtig benutzt.

## Umgebung (Entwicklerrechner)

- **Eclipse mit ADT:** `C:\ADT\eclipse` (4.41, Java 25 über JustJ). Workspace:
  `C:\ADT\workspace`. Das Repository liegt unter `C:\ADT\workspace\claudecode-eclipse-adt`.
- **JDK:** das JustJ-JDK in `C:\ADT\eclipse\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.*\jre`
  (enthält `javac`, `jar`, `jstack`). Maven und `cargo` gibt es nicht.
- **Git:** `C:\git\bin\git.exe` (portabel, nicht im `PATH`).
- **Shell:** Windows PowerShell 5.1 (siehe Fallstricke).
- **Netzwerk:** GitHub über `git` funktioniert. Direkte HTTP-Aufrufe ohne Client sind
  wegen Proxy/Firewall schwierig.
- Eine Claude-Sitzung, die in einer Eclipse-View läuft, **endet, wenn Eclipse beendet wird**.
  Hintergrundjobs der Sitzung sterben mit. Was einen Neustart überdauern muss, startest du
  als eigenen Prozess (`Start-Process`).

## Bauen, installieren, verteilen

```powershell
.\build-local.ps1                 # baut build-local\com.anthropic.claudecode.eclipse.adt_<version>.jar
.\build-local.ps1 -Install        # zusätzlich nach C:\ADT\eclipse\dropins (nur bei beendetem Eclipse)
.\build-local.ps1 -UpdateSite     # zusätzlich build-local\ClaudeCodeADT-<version>.zip (p2, offline)
```

- Die Version bekommt den Build-Zeitpunkt als Qualifier. Jeder Build ist also neuer.
- **Eclipse sperrt die geladene Jar.** Während Eclipse läuft, kann `-Install` sie nicht
  ersetzen. Dann den Build ohne `-Install` erstellen und `scripts\install-on-exit.ps1` als
  eigenen Prozess starten (Aufruf im Kopf des Skripts). Der Nutzer startet Eclipse danach
  neu.
- **Installation über die Update-Site** (*Install New Software > Archive*) ist der Weg für
  andere Rechner. Dort wird über dieselbe Funktion aktualisiert, nicht über `dropins`.
  Niemals beides gleichzeitig: Das wäre dasselbe Singleton-Bundle.
- Prüfe nach dem Neustart in `C:\ADT\workspace\.metadata\.log`, ob
  `com.anthropic.claudecode.eclipse.adt` startet („HTTP+SSE server (Rust) listening …“).

## Testen gegen das laufende Eclipse

```powershell
.\scripts\mcp-probe.ps1 -Tool getOpenEditors
.\scripts\mcp-probe.ps1 -Tool readEditor -ArgsJson '{"file_path":"ZGRAFFLCA_TEST","line_numbers":true}'
.\scripts\mcp-probe.ps1 -Tool adtCommand -ArgsJson '{"action":"checkSyntax","file_path":"ZGRAFFLCA_TEST"}'
```

- Das Skript findet den Port über `~\.claude\ide\*.lock` mit `ideName` „Eclipse (ADT)“. Das
  Original-Plugin meldet sich dort als „Eclipse“.
- Es zeigt die Zeit jeder Antwort. So fielen schon Aufrufe auf, die erst nach Ablauf ihrer
  Wartezeit antworteten.
- Der Aufruf **umgeht die Freigabe-Karten** und ändert wirklich etwas. Ändernde Tests nur an
  Testobjekten (z. B. `ZGRAFFLCA_TEST`) und nur, wenn der Nutzer zugestimmt hat. Danach den
  Ausgangszustand wiederherstellen.
- Hängt ein Aufruf, hilft ein Thread-Dump: `jstack <eclipse-pid>`. Interessant sind die
  Threads `main` und `claude-tool-call`.
- Stürzt Eclipse ohne Meldung ab, liegt `C:\ADT\eclipse\hs_err_pid*.log` vor. Ein Absturz in
  `claude_eclipse_adt_core.dll` deutet auf eine Bibliothek, die nicht zu `NativeCore.java`
  passt (Regel 4).

## Fallstricke, die bereits aufgetreten sind

- **Veraltete Bibliotheken im Upstream-Quellbaum:** Die eingecheckte Windows-DLL kannte nur
  79 der 96 nativen Methoden, und die JVM stürzte beim ersten Prompt ab. Upstream baut sie
  erst beim Release. Bibliotheken deshalb aus dem passenden Release-Jar übernehmen.
- **`semanticfs:`-Pfade:** `Path.of("semanticfs:/…")` wirft unter Windows eine Exception. Für
  Editoren immer `EditorTargets`, für Dateien `EditorTargets.resolveFile` nehmen.
- **ADT ohne Anmeldung:** Nach einem Neustart zeigen ADT-Editoren nur einen Platzhalter, bis
  sich der Nutzer am SAP-System angemeldet hat. Ausgelöst wird die Anmeldung durch einen
  Klick in den Editor, nicht durch Aktivieren. Die Tools melden dann „is not a text editor …
  probably not logged on“.
- **Warten auf ADT-Jobs:** Nicht nach Jobnamen suchen. Irgendein Job mit „check“ im Namen
  läuft immer. `AdtCommandTool.JobWatch` wartet auf genau die Jobs, die der Befehl startet.
- **Modale Dialoge sperren die Freigabe-Karte** der View „Claude Code (ADT)“. Deshalb fragt
  `EclipseDialogTool.approveOverModalDialog` in einem eigenen Dialog über dem offenen Dialog.
  Im **Claude Terminal (ADT)** gibt es dafür keine Lösung: Dort steht die Freigabe im
  Terminal.
- **ADT hängt beim Speichern ein Zeilenende an.** Ein ungespeichertes Dokument ohne
  abschließendes Zeilenende sieht nach dem Speichern anders aus.
- **Windows PowerShell 5.1:**
  - `Compress-Archive` schreibt ZIP-Einträge mit `\`. p2 findet dann keine Artefakte. Zum
    Packen nimmt man `jar --create --no-manifest`.
  - Argumente mit eingebetteten `"` oder leere Strings kommen bei nativen Programmen falsch
    an. Commit-Nachrichten deshalb über `git commit -F <datei>`, leere Werte über `--%`.
  - Stderr nativer Programme wird bei `$ErrorActionPreference = 'Stop'` zum Fehler. Um
    solche Aufrufe herum `Continue` setzen.
  - `Set-Content`/`Out-File` schreiben je nach Variante BOM oder ANSI. Dateien mit
    `[IO.File]::WriteAllText(…, New-Object System.Text.UTF8Encoding($false))` schreiben. Das
    Repo arbeitet mit LF und ohne BOM.
- **`TerminalScrollbar.java`** braucht die GTK-Interna von SWT. Unter Windows kompiliert
  `build-local.ps1` die Klasse mit ecj und `-proceedOnError`. Das ist gewollt.

## Git und GitHub

- Remote `origin` = `https://github.com/dg-cg/claudecode-eclipse-adt` (Fork des Upstreams).
- Branches: `dev` ist der unveränderte Upstream-Stand (3.2.19), `adt` die Arbeit des Forks.
- Lokale Repo-Konfiguration:
  - `core.autocrlf=false` (Dateien mit LF)
  - Autor `Carsten Grafflage <dg-cg@users.noreply.github.com>`
  - Credential-Helfer Git Credential Manager (`C:/git/mingw64/bin/git-credential-manager.exe`)
- **Push aus einer Agent-Shell:** Der Credential Manager hält sie für nicht interaktiv.
  Deshalb `$env:GCM_INTERACTIVE='always'` setzen und nicht `GIT_TERMINAL_PROMPT=0`.
  Für die erste Anmeldung öffnet sich ein Fenster, das der Nutzer bedient.
- Push und alles andere, was nach außen geht, nur nach Rückfrage beim Nutzer. Commits sind
  öffentlich.
- **Upstream einarbeiten:**
  1. Den Upstream als Remote hinzufügen und `dev` auf die neue Upstream-Version bringen.
  2. `dev` nach `adt` mergen.
  3. Danach die nativen Bibliotheken aus dem Release-Jar dieser Version übernehmen und in
     `claude_eclipse_adt_core.*` umbenennen.
  4. Neue IDs in `plugin.xml` mit `.adt` versehen (Regel 2).
  5. Neue Java-ID-Strings ebenso.
  6. Neue globale Tastenkürzel entfernen (Regel 3).
  7. `build-local.ps1` laufen lassen.

## Offene Punkte

- Die native Bibliothek kennt noch fest „Eclipse“ und den MCP-Namen `eclipse`. In der
  Lock-Datei korrigiert Java den Namen. Für mehr bräuchte es einen Rust-Neubau.
- `.github/workflows` sind nicht an den Fork angepasst.
- Die Tests unter `test/` laufen nur im Tycho-Build und sind für den Fork nicht ausgeführt.
- Nicht getestet: ob die Update-Site auf Azure-Virtual-Desktop-Instanzen installierbar ist
  (nicht signiert, DLL-Laden unter Richtlinien).
