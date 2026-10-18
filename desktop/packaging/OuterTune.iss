; OuterTune's Windows installer. Built by `gradlew :desktop:installer`, which passes:
;   SourceDir  - the jpackage app image (OuterTune.exe, app\, runtime\)
;   OutDir     - where OuterTune-setup.exe goes
;   AppVersion - the app's version
;   IconFile   - packaging\OuterTune.ico
;
; Per-user by default, so installing needs no administrator prompt; the user can choose all users.
; One solid LZMA2 block over everything is where the size saving over the zip comes from.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{6B0E7D52-3C1F-4C8E-9C61-0F7A3E5B2D41}
AppName=OuterTune
AppVersion={#AppVersion}
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
OutputBaseFilename=OuterTune-setup
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\OuterTune.exe
Compression=lzma2/ultra64
SolidCompression=yes
LZMANumBlockThreads=4
WizardStyle=modern
; The library, settings and downloads live in the user's profile, not here, so uninstalling the app
; never touches them.
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\OuterTune"; Filename: "{app}\OuterTune.exe"
Name: "{autodesktop}\OuterTune"; Filename: "{app}\OuterTune.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\OuterTune.exe"; Description: "{cm:LaunchProgram,OuterTune}"; Flags: nowait postinstall skipifsilent
