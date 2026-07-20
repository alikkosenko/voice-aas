# Runtime recovery after installation

After the password is accepted, AAS 1.3.2 immediately:

1. initializes the process-wide voice runtime;
2. preloads the selected Vosk model;
3. starts the foreground watchdog;
4. runs an immediate ADB/helper repair;
5. requests the ADB RSA authorization dialog when the new key is not trusted;
6. enables/binds the AAS accessibility service through the helper.

For a clean test, uninstall the old package, reboot the multimedia, install 1.3.2, open AAS, pass authorization and accept the ADB RSA dialog.
