import json
import datetime

dates = []
current_date = datetime.date(2025, 5, 14)
for _ in range(50):
    dates.append(current_date.strftime("%d-%m-%Y"))
    current_date += datetime.timedelta(days=14)

metadata = {}
for idx, d in enumerate(dates):
    metadata[d] = {
        "invitados": f"Invitado {idx + 1}",
        "tema": f"Tema del episodio {idx + 1}",
        "tags": ["podcast", f"tag{idx+1}"]
    }

with open('/home/rtorgil/AndroidStudioProjects/TXLVDownloader/app/src/main/assets/metadata.json', 'w') as f:
    json.dump(metadata, f, indent=4)

