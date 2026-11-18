# Requests from lane P-SR1 (android-parity-sr-1) to the lead

## R-PAR-SR1-01: online address on Attendance (display only)
The reference shows a reverse-geocoded address ("Kamal Ataturk Avenue, Banani, ...") on the Attendance location card; ours shows coordinates and accuracy (offline-first, UI-SR-11). If the owner wants the address text, it needs a server or Maps reverse-geocode call made only while online, cached with the fix and never in the check-in path. A behaviour and contract change, so not built in this lane.

