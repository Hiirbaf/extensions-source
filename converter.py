#!/usr/bin/env python3
from pathlib import Path
from rich.progress import Progress, TextColumn, BarColumn, TimeRemainingColumn
import subprocess
import sys

# === Solicitar la carpeta de entrada si no se pasó por argumento ===
if len(sys.argv) > 1:
    root_input_dir = Path(sys.argv[1])
else:
    user_input = input("📂 Ingresa la ruta de la carpeta que contiene las imágenes JPG: ").strip()
    if not user_input:
        print("❌ No se ingresó ninguna ruta. Saliendo.")
        sys.exit(1)
    root_input_dir = Path(user_input)

# Verificar que la carpeta sea válida
if not root_input_dir.is_dir():
    print(f"❌ Error: {root_input_dir} no es una carpeta válida")
    sys.exit(1)

# Carpeta raíz de salida
root_output_dir = root_input_dir.parent / f"{root_input_dir.name}_webp"
root_output_dir.mkdir(exist_ok=True)

# Preguntar si borrar originales
delete_originals = input("¿Querés borrar los JPG originales después de convertir? (y/n): ").strip().lower() == "y"

# Buscar archivos .jpg (sin importar mayúsculas/minúsculas)
jpg_files = [p for p in root_input_dir.rglob("*") if p.suffix.lower() == ".jpg"]
total_files = len(jpg_files)

if total_files == 0:
    print(f"\nℹ️ No se encontraron archivos JPG en: {root_input_dir}")
    sys.exit(0)

# Función para mantener estructura de carpetas
def output_path(jpg_path):
    relative = jpg_path.relative_to(root_input_dir)
    out_path = root_output_dir / relative.parent / f"{jpg_path.stem}.webp"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    return out_path

# Barra de progreso con Rich
with Progress(
    TextColumn(" [yellow][{task.completed}/{task.total}][/yellow] [green]Convirtiendo imágenes...[/green] {task.percentage:>3.0f}%"),
    BarColumn(),
    TimeRemainingColumn(),
) as progress:
    task = progress.add_task("", total=total_files)

    for jpg_path in jpg_files:
        webp_path = output_path(jpg_path)
        subprocess.run(["cwebp", "-q", "85", str(jpg_path), "-o", str(webp_path)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if delete_originals:
            jpg_path.unlink()
        progress.update(task, advance=1)

print(f"\n✅ Conversión completada. Archivos guardados en: {root_output_dir}")
if delete_originals:
    print("⚠️ Archivos JPG originales borrados.")
