import re

with open('app/src/main/java/com/rtorgil/txlv_downloader/MainActivity.kt', 'r') as f:
    content = f.read()

imports_to_add = [
    "import androidx.compose.foundation.Image",
    "import androidx.compose.ui.res.painterResource",
    "import androidx.compose.ui.layout.ContentScale",
    "import androidx.compose.material.icons.filled.PlayArrow"
]

for imp in imports_to_add:
    if imp not in content:
        content = content.replace("import android.os.Bundle", f"{imp}\nimport android.os.Bundle")

with open('app/src/main/java/com/rtorgil/txlv_downloader/MainActivity.kt', 'w') as f:
    f.write(content)
