; A per-user Windows development installer for ape-get.
; Payload is built with APEGET_SELF=OFF, so setup owns installation/PATH.

#define AppVersion "2.0.0-dev"

[Setup]
AppId={{88C2F3B5-24A0-4A47-9D5C-0483E08B03D7}
AppName=ape-get (Development)
AppVersion={#AppVersion}
AppPublisher=The Omni Stuart
AppPublisherURL=https://github.com/The-Omni-Stuart/ape-get
DefaultDirName={localappdata}\Programs\ape-get-dev
DefaultGroupName=ape-get (Development)
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ChangesEnvironment=yes
WizardStyle=modern
OutputDir=output
OutputBaseFilename=ape-get-dev-setup
Compression=lzma
SolidCompression=yes
UninstallDisplayName=ape-get (Development)

[Files]
Source: "payload\ape-get.com"; DestDir: "{app}"; Flags: ignoreversion

[Tasks]
Name: "addtopath"; Description: "Add ape-get to my PATH (recommended)"; GroupDescription: "Command-line integration:"

[Code]
const
  EnvKey = 'Environment';
  StateKey = 'Software\ape-get-dev\Installer';

function PathContains(const PathValue, Dir: String): Boolean;
var
  Remaining, Piece: String;
  I: Integer;
begin
  Result := False;
  Remaining := PathValue;
  while Remaining <> '' do
  begin
    I := Pos(';', Remaining);
    if I = 0 then
    begin
      Piece := Remaining;
      Remaining := '';
    end
    else
    begin
      Piece := Copy(Remaining, 1, I - 1);
      Delete(Remaining, 1, I);
    end;
    if CompareText(Trim(Piece), Dir) = 0 then
    begin
      Result := True;
      Exit;
    end;
  end;
end;

function WithoutPathEntry(const PathValue, Dir: String): String;
var
  Remaining, Piece: String;
  I: Integer;
begin
  Result := '';
  Remaining := PathValue;
  while Remaining <> '' do
  begin
    I := Pos(';', Remaining);
    if I = 0 then
    begin
      Piece := Remaining;
      Remaining := '';
    end
    else
    begin
      Piece := Copy(Remaining, 1, I - 1);
      Delete(Remaining, 1, I);
    end;
    if (Piece <> '') and (CompareText(Trim(Piece), Dir) <> 0) then
    begin
      if Result <> '' then
        Result := Result + ';';
      Result := Result + Piece;
    end;
  end;
end;

procedure RemoveOwnedPath;
var
  Added: Cardinal;
  StoredDir, CurrentPath, UpdatedPath: String;
begin
  if not RegQueryDWordValue(HKCU, StateKey, 'AddedToPath', Added) then Exit;
  if Added <> 1 then Exit;
  if not RegQueryStringValue(HKCU, StateKey, 'PathEntry', StoredDir) then Exit;

  if RegQueryStringValue(HKCU, EnvKey, 'Path', CurrentPath) then
  begin
    UpdatedPath := WithoutPathEntry(CurrentPath, StoredDir);
    if UpdatedPath <> CurrentPath then
      if not RegWriteExpandStringValue(HKCU, EnvKey, 'Path', UpdatedPath) then
      begin
        Log('Could not remove development directory from user PATH');
        Exit;
      end;
  end;
  RegDeleteValue(HKCU, StateKey, 'AddedToPath');
  RegDeleteValue(HKCU, StateKey, 'PathEntry');
  RegDeleteKeyIfEmpty(HKCU, StateKey);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Dir, PreviousDir, CurrentPath, NewPath: String;
begin
  if CurStep <> ssPostInstall then Exit;

  Dir := ExpandConstant('{app}');
  if not WizardIsTaskSelected('addtopath') then
  begin
    RemoveOwnedPath;
    Exit;
  end;

  // On a reinstall to a different directory, first remove our previous entry.
  if RegQueryStringValue(HKCU, StateKey, 'PathEntry', PreviousDir) then
    if CompareText(PreviousDir, Dir) <> 0 then
      RemoveOwnedPath;

  if not RegQueryStringValue(HKCU, EnvKey, 'Path', CurrentPath) then
    CurrentPath := '';
  if PathContains(CurrentPath, Dir) then Exit;

  if CurrentPath = '' then
    NewPath := Dir
  else
    NewPath := Dir + ';' + CurrentPath;

  if RegWriteExpandStringValue(HKCU, EnvKey, 'Path', NewPath) then
  begin
    RegWriteStringValue(HKCU, StateKey, 'PathEntry', Dir);
    RegWriteDWordValue(HKCU, StateKey, 'AddedToPath', 1);
  end
  else
    MsgBox('ape-get installed, but Setup could not update your user PATH.',
      mbError, MB_OK);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    RemoveOwnedPath;
end;
