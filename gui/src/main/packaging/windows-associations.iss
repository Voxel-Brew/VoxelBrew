[Registry]
Root: HKCU; Subkey: "Software\Classes\.vxb"; ValueType: string; ValueName: ""; ValueData: "VoxelBrew.Design"; Check: NoDefaultHandler; Flags: uninsdeletekeyifempty
Root: HKCU; Subkey: "Software\Classes\.vxb\OpenWithProgids"; ValueType: string; ValueName: "VoxelBrew.Design"; ValueData: ""; Flags: uninsdeletevalue uninsdeletekeyifempty
Root: HKCU; Subkey: "Software\Classes\VoxelBrew.Design"; ValueType: string; ValueName: ""; ValueData: "VoxelBrew design"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\VoxelBrew.Design\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\VoxelBrew.exe"",0"
Root: HKCU; Subkey: "Software\Classes\VoxelBrew.Design\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\VoxelBrew.exe"" --open ""%1"""

[Code]
function NoDefaultHandler: Boolean;
begin
  Result := not RegValueExists(HKCR, '.vxb', '');
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DefaultHandler: String;
begin
  if CurUninstallStep = usUninstall then
    if RegQueryStringValue(HKCU, 'Software\Classes\.vxb', '', DefaultHandler) then
      if DefaultHandler = 'VoxelBrew.Design' then
        RegDeleteValue(HKCU, 'Software\Classes\.vxb', '');
end;
