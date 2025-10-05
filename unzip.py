#!/usr/bin/env python3
"""
Script para descomprimir archivos CBZ/CBR con lógica inteligente:
- Si las imágenes están sueltas en el archivo: crea carpeta con nombre del archivo
- Si hay una subcarpeta con las imágenes: extrae solo el contenido de la subcarpeta
"""

import os
import zipfile
import tempfile
import shutil
import subprocess
from pathlib import Path
from typing import List, Set, Optional
import argparse

# Intentar importar rarfile, pero continuar sin él si no está disponible
try:
    import rarfile
    RARFILE_AVAILABLE = True
except ImportError:
    RARFILE_AVAILABLE = False
    rarfile = None

# Extensiones de imagen válidas
IMAGE_EXTENSIONS: Set[str] = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".tiff", ".tif"}

def is_image_file(filename: str) -> bool:
    """Verifica si un archivo es una imagen basándose en su extensión."""
    return Path(filename).suffix.lower() in IMAGE_EXTENSIONS

def check_rar_support() -> bool:
    """Verifica si hay soporte para archivos RAR disponible."""
    if not RARFILE_AVAILABLE:
        return False
    
    # Verificar si rarfile puede encontrar una herramienta RAR
    try:
        rarfile.UNRAR_TOOL
        return True
    except rarfile.RarCannotExec:
        return False

def get_archive_contents(archive_path: Path) -> List[str]:
    """Obtiene la lista de archivos dentro del archivo comprimido."""
    if archive_path.suffix.lower() == '.cbz':
        with zipfile.ZipFile(archive_path, 'r') as zip_file:
            return zip_file.namelist()
    elif archive_path.suffix.lower() == '.cbr':
        if not RARFILE_AVAILABLE:
            raise ValueError("rarfile no está instalado. Instala con: pip install rarfile")
        if not check_rar_support():
            raise ValueError("No se encontró herramienta RAR. Instala unrar o p7zip")
        with rarfile.RarFile(archive_path, 'r') as rar_file:
            return rar_file.namelist()
    else:
        raise ValueError(f"Formato de archivo no soportado: {archive_path.suffix}")

def extract_archive_to_temp(archive_path: Path, temp_dir: Path) -> None:
    """Extrae el archivo comprimido a un directorio temporal."""
    if archive_path.suffix.lower() == '.cbz':
        with zipfile.ZipFile(archive_path, 'r') as zip_file:
            zip_file.extractall(temp_dir)
    elif archive_path.suffix.lower() == '.cbr':
        if not RARFILE_AVAILABLE:
            raise ValueError("rarfile no está instalado. Instala con: pip install rarfile")
        if not check_rar_support():
            raise ValueError("No se encontró herramienta RAR. Instala unrar o p7zip")
        with rarfile.RarFile(archive_path, 'r') as rar_file:
            rar_file.extractall(temp_dir)

def analyze_archive_structure(contents: List[str]) -> tuple[bool, Optional[str]]:
    """
    Analiza la estructura del archivo y determina:
    - Si las imágenes están en el root (True) o en una subcarpeta (False)
    - El nombre de la subcarpeta principal si existe
    
    Returns:
        tuple: (images_in_root, main_folder_name)
    """
    # Filtrar solo archivos de imagen
    image_files = [f for f in contents if is_image_file(f)]
    
    if not image_files:
        return True, None  # No hay imágenes, tratar como root
    
    # Verificar si hay imágenes directamente en el root
    root_images = [f for f in image_files if '/' not in f and '\\' not in f]
    
    if root_images:
        return True, None  # Hay imágenes en root
    
    # Todas las imágenes están en subcarpetas
    # Encontrar la carpeta principal más común
    folders = set()
    for img_file in image_files:
        # Normalizar separadores de directorio
        normalized_path = img_file.replace('\\', '/')
        if '/' in normalized_path:
            folder = normalized_path.split('/')[0]
            folders.add(folder)
    
    if len(folders) == 1:
        return False, list(folders)[0]
    else:
        # Múltiples carpetas, tratar como estructura compleja
        return True, None

def extract_cbz_cbr(archive_path: Path, output_dir: Path, dry_run: bool = False) -> bool:
    """
    Extrae un archivo CBZ/CBR siguiendo la lógica especificada.
    
    Args:
        archive_path: Ruta al archivo CBZ/CBR
        output_dir: Directorio donde extraer
        dry_run: Si es True, solo muestra qué haría sin extraer realmente
    
    Returns:
        bool: True si la extracción fue exitosa
    """
    try:
        # Obtener contenido del archivo
        contents = get_archive_contents(archive_path)
        
        # Analizar estructura
        images_in_root, main_folder = analyze_archive_structure(contents)
        
        # Nombre base para la carpeta de salida
        base_name = archive_path.stem
        
        if dry_run:
            if images_in_root:
                print(f"[DRY RUN] {archive_path.name}: Imágenes en root -> crear carpeta '{base_name}'")
            else:
                print(f"[DRY RUN] {archive_path.name}: Imágenes en subcarpeta '{main_folder}' -> extraer solo contenido de subcarpeta")
            return True
        
        if images_in_root:
            # Crear carpeta con nombre del archivo y extraer todo
            target_dir = output_dir / base_name
            target_dir.mkdir(exist_ok=True)
            
            print(f"Extrayendo {archive_path.name} -> {target_dir}")
            extract_archive_to_temp(archive_path, target_dir)
            
        else:
            # Extraer a temporal y luego mover solo el contenido de la subcarpeta
            with tempfile.TemporaryDirectory() as temp_dir:
                temp_path = Path(temp_dir)
                
                print(f"Extrayendo {archive_path.name} -> extrayendo subcarpeta '{main_folder}'")
                extract_archive_to_temp(archive_path, temp_path)
                
                # Mover contenido de la subcarpeta principal al directorio de salida
                source_folder = temp_path / main_folder
                target_dir = output_dir / base_name
                
                if source_folder.exists():
                    if target_dir.exists():
                        shutil.rmtree(target_dir)
                    shutil.move(str(source_folder), str(target_dir))
                else:
                    print(f"Advertencia: No se encontró la subcarpeta '{main_folder}' en {archive_path.name}")
                    return False
        
        print(f"✓ Completado: {archive_path.name}")
        return True
        
    except Exception as e:
        print(f"✗ Error procesando {archive_path.name}: {e}")
        return False

def find_comic_archives(directory: Path) -> List[Path]:
    """Encuentra todos los archivos CBZ/CBR en el directorio."""
    archives = []
    for ext in ['*.cbz', '*.cbr', '*.CBZ', '*.CBR']:
        archives.extend(directory.glob(ext))
    return sorted(archives)

def main():
    parser = argparse.ArgumentParser(description='Extrae archivos CBZ/CBR con lógica inteligente')
    parser.add_argument('input_dir', nargs='?', default='.', help='Directorio con archivos CBZ/CBR (por defecto: directorio actual)')
    parser.add_argument('-o', '--output', help='Directorio de salida (por defecto: mismo que entrada)')
    parser.add_argument('--dry-run', action='store_true', help='Solo mostrar qué se haría sin extraer')
    parser.add_argument('--verbose', '-v', action='store_true', help='Mostrar más detalles')
    parser.add_argument('--skip-rar', action='store_true', help='Omitir archivos CBR/RAR')
    
    args = parser.parse_args()
    
    input_dir = Path(args.input_dir)
    if not input_dir.exists() or not input_dir.is_dir():
        print(f"Error: El directorio '{input_dir}' no existe")
        return 1
    
    output_dir = Path(args.output) if args.output else input_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    
    # Verificar soporte RAR
    rar_supported = check_rar_support()
    if not rar_supported and not args.skip_rar:
        print("⚠️  Advertencia: No se detectó soporte para archivos RAR (.cbr)")
        print("   Para habilitar soporte RAR:")
        print("   1. Instala rarfile: pip install rarfile")
        print("   2. Instala una herramienta RAR:")
        print("      - En Termux/Android: pkg install unrar")
        print("      - En Ubuntu/Debian: apt install unrar")
        print("      - En macOS: brew install unrar")
        print("   3. O usa --skip-rar para omitir archivos CBR")
        print()
    
    # Encontrar archivos
    archives = find_comic_archives(input_dir)
    
    if args.skip_rar or not rar_supported:
        # Filtrar archivos RAR si no hay soporte
        original_count = len(archives)
        archives = [a for a in archives if a.suffix.lower() != '.cbr']
        if original_count > len(archives):
            print(f"Omitiendo {original_count - len(archives)} archivo(s) CBR")
    
    if not archives:
        print(f"No se encontraron archivos CBZ/CBR procesables en '{input_dir}'")
        return 1
    
    print(f"Encontrados {len(archives)} archivo(s) para procesar")
    print(f"Directorio de salida: {output_dir}")
    
    if args.dry_run:
        print("\n=== MODO DRY RUN - NO SE EXTRAERÁ NADA ===")
    
    print()
    
    # Procesar archivos
    successful = 0
    failed = 0
    
    for archive in archives:
        if extract_cbz_cbr(archive, output_dir, args.dry_run):
            successful += 1
        else:
            failed += 1
    
    print(f"\nResultados:")
    print(f"  ✓ Exitosos: {successful}")
    print(f"  ✗ Fallidos: {failed}")
    
    return 0 if failed == 0 else 1

if __name__ == "__main__":
    exit(main())
