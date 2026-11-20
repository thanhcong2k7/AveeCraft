#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef AppSourceDir
  #define AppSourceDir "..\..\build\jpackage\AveeCraft"
#endif
#ifndef AppIcon
  #define AppIcon "..\..\build\icon\aveecraft.ico"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\build\release"
#endif

[Setup]
AppId={{8E9A54A2-7D34-4B79-B61C-24E2A9B6C101}
AppName=AveeCraft
AppVersion={#AppVersion}
AppVerName=AveeCraft {#AppVersion}
AppPublisher=AveeCraft
DefaultDirName={autopf}\AveeCraft
DefaultGroupName=AveeCraft
UninstallDisplayIcon={app}\AveeCraft.exe
SetupIconFile={#AppIcon}
OutputDir={#OutputDir}
OutputBaseFilename=AveeCraft-windows-x64-setup
Compression=lzma2/normal
SolidCompression=yes
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#AppSourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\AveeCraft"; Filename: "{app}\AveeCraft.exe"
Name: "{group}\Uninstall AveeCraft"; Filename: "{uninstallexe}"
Name: "{autodesktop}\AveeCraft"; Filename: "{app}\AveeCraft.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\AveeCraft.exe"; Description: "{cm:LaunchProgram,AveeCraft}"; Flags: nowait postinstall skipifsilent
