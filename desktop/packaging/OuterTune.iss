; OuterTune's Windows installer. Built by `gradlew :desktop:installer`, which passes:
;   SourceDir  - the jpackage app image (OuterTune.exe, app\, runtime\)
;   OutDir     - where OuterTune-setup.exe goes
;   AppVersion - the app's version
;   IconFile   - packaging\OuterTune.ico
;   FilesList  - a generated [Files] list: every file with its SHA-256 (see build.gradle.kts)
;   Manifest   - a generated list of every file shipped, installed as {app}\manifest.txt
;
; Per-user by default, so installing needs no administrator prompt; the user can choose all users.
; One solid LZMA2 block over everything is where the size saving over the zip comes from.
;
; Over an existing install it is an update, not a reinstall:
; - It says so, and skips the folder, shortcut and ready-to-install questions already answered -
;   one page, "Update OuterTune 0.3a to 0.3b", and the button says Update.
; - A file whose installed copy is already identical (same SHA-256) is left alone; only what
;   changed is written. The bundled Java runtime rarely changes between versions, so most of an
;   update is not rewritten at all.
; - Files the previous version shipped and this one does not are removed, from the manifest each
;   install leaves behind, so an update cannot leave a stale jar or library lying around.
; - OuterTune is closed for the update and opened again after.
; The library, settings and downloads live in the user's profile, not here, so neither an update
; nor an uninstall touches them.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
; Digits only, for the file name the app's updater looks for: OuterTune-setup-<x.y.z>.exe.
#ifndef AppNumeric
  #define AppNumeric "0.0.0"
#endif

[Setup]
AppId={{6B0E7D52-3C1F-4C8E-9C61-0F7A3E5B2D41}
AppName=OuterTune
AppVersion={#AppVersion}
AppVerName=OuterTune {#AppVersion}
AppPublisher=OuterTune Project
AppPublisherURL=https://github.com/notK50BML/OuterTune
DefaultDirName={autopf}\OuterTune
DefaultGroupName=OuterTune
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutDir}
OutputBaseFilename=OuterTune-setup-{#AppNumeric}
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\OuterTune.exe
Compression=lzma2/ultra64
SolidCompression=yes
LZMANumBlockThreads=4
WizardStyle=modern
CloseApplications=yes
RestartApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
#include FilesList
Source: "{#Manifest}"; DestDir: "{app}"; DestName: "manifest.txt"; Flags: ignoreversion

[UninstallDelete]
; The class-data cache the app writes beside its jar on first run, for a faster start.
Type: files; Name: "{app}\app\app.jsa"

[Icons]
Name: "{autoprograms}\OuterTune"; Filename: "{app}\OuterTune.exe"
Name: "{autodesktop}\OuterTune"; Filename: "{app}\OuterTune.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\OuterTune.exe"; Description: "{cm:LaunchProgram,OuterTune}"; Flags: nowait postinstall skipifsilent
; An update from inside the app runs silently and closes the app first - so it is opened again here.
Filename: "{app}\OuterTune.exe"; Flags: nowait; Check: WizardSilent

[Code]
const
  UninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{6B0E7D52-3C1F-4C8E-9C61-0F7A3E5B2D41}_is1';

var
  OldManifest: TStringList;

{ The version already installed, or '' when this is a fresh install. }
function InstalledVersion: String;
begin
  Result := '';
  if not RegQueryStringValue(HKCU, UninstallKey, 'DisplayVersion', Result) then
    RegQueryStringValue(HKLM, UninstallKey, 'DisplayVersion', Result);
end;

function IsUpdate: Boolean;
begin
  Result := InstalledVersion <> '';
end;

{ Whether a file needs writing: missing, or different from the one being shipped. }
function Changed(const RelPath, Sha256: String): Boolean;
var
  Target: String;
begin
  Target := ExpandConstant('{app}\') + RelPath;
  if not FileExists(Target) then
    Result := True
  else
    Result := CompareText(GetSHA256OfFile(Target), Sha256) <> 0;
end;

procedure InitializeWizard;
begin
  if IsUpdate then
    WizardForm.Caption := 'Update OuterTune';
end;

{ An update has already been told where it lives and which shortcuts it wants. }
function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := IsUpdate and ((PageID = wpSelectDir) or (PageID = wpSelectTasks));
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if IsUpdate and (CurPageID = wpReady) then
  begin
    WizardForm.ReadyLabel.Caption :=
      'Update OuterTune ' + InstalledVersion + ' to {#AppVersion}.' + #13#10#13#10 +
      'Only files that changed are replaced. Your library, playlists, downloads and settings are not touched.';
    WizardForm.ReadyMemo.Visible := False;
    WizardForm.NextButton.Caption := 'Update';
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  NewManifest: TStringList;
  I: Integer;
begin
  if CurStep = ssInstall then
  begin
    { What the version being replaced shipped, read before this one's list overwrites it. }
    OldManifest := TStringList.Create;
    if FileExists(ExpandConstant('{app}\manifest.txt')) then
      OldManifest.LoadFromFile(ExpandConstant('{app}\manifest.txt'));
  end;
  if CurStep = ssPostInstall then
  begin
    NewManifest := TStringList.Create;
    try
      NewManifest.LoadFromFile(ExpandConstant('{app}\manifest.txt'));
      for I := 0 to OldManifest.Count - 1 do
        if (Trim(OldManifest[I]) <> '') and (NewManifest.IndexOf(OldManifest[I]) < 0) then
          DeleteFile(ExpandConstant('{app}\') + OldManifest[I]);
    finally
      NewManifest.Free;
      OldManifest.Free;
    end;
  end;
end;
