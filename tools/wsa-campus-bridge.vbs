' Starts the WSA campus proxy bridge without a console window.
'
' Campus addresses (172.18.0.0/16) overlap WSA's own NAT (172.18.16.0/20), so the
' guest can only reach the school through the host proxy; this relay is that path.
' The interpreter is the project's own conda env because that python.exe already
' has an inbound allow rule in Windows Firewall — the base conda pythonw.exe is
' explicitly blocked, and a blocked listener is invisible from the guest.
'
' To stop it:  taskkill /IM python.exe /FI "WINDOWTITLE eq wsa-campus-bridge*"
' or simply remove the HKCU Run value "SustechWsaBridge" and reboot.
Set shell = CreateObject("WScript.Shell")
shell.Run """D:\dumix\Applications\conda\envs\sustech-env\python.exe"" ""D:\dumix\projects\sustech-mobile\tools\wsa-campus-bridge.py""", 0, False
