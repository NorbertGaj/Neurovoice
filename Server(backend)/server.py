import logging
import os
import time
import uuid
import shutil
import zipfile
import io
import base64
import re
import tempfile
from flask import Flask, request, jsonify
from TTS.api import TTS
import torch
from pydub import AudioSegment
import xml.etree.ElementTree as ET
from ebooklib import epub, ITEM_DOCUMENT
from bs4 import BeautifulSoup
from charset_normalizer import detect

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Inicjalizacja TTS
device = "cuda" if torch.cuda.is_available() else "cpu"
logger.info(f"Używane urządzenie: {device}")
tts = TTS(model_name="tts_models/multilingual/multi-dataset/xtts_v2", progress_bar=False).to(device)

def text_similarity(text1: str, text2: str, sample_size: int = 200) -> float:
    """Oblicza przybliżone podobieństwo między dwoma tekstami na podstawie zbioru znaków.
    
    Args:
        text1 (str): Pierwszy tekst do porównania
        text2 (str): Drugi tekst do porównania
        sample_size (int): Liczba znaków do pobrania z każdego tekstu
        
    Returns:
        float: Wynik podobieństwa od 0.0 do 1.0
    """
    if not text1 or not text2:
        return 0.0
    
    t1_sample = text1[:sample_size] if len(text1) > sample_size else text1
    t2_sample = text2[:sample_size] if len(text2) > sample_size else text2
    
    common_chars = set(t1_sample) & set(t2_sample)
    if not common_chars:
        return 0.0
    
    return len(common_chars) / max(len(set(t1_sample)), len(set(t2_sample)))

def clean_text(text: str) -> str:
    """Czysci tekst, usuwając niechciane wzorce, symbole i formatowanie.
    
    Args:
        text (str): Tekst wejściowy do oczyszczenia
        
    Returns:
        str: Oczyszczony tekst
    """
    patterns = [
        r'(Ta lektura, podobnie jak tysiące innych, jest dostępna on-line na stronie wolnelektury\.pl\..*?ISBN-----).*?\1',
        r'(ISBN-{3,})'
    ]
    
    for pattern in patterns:
        text = re.sub(pattern, r'\1', text, flags=re.DOTALL)
    
    text = text.replace("\xa0", " ")
    text = re.sub(r'([a-zA-ZęóąśłżźćńĘÓĄŚŁŻŹĆŃ]+)(\d+)', r'\1', text)
    text = re.sub(r'\[\d+\]', '', text)
    text = re.sub(r'\[.*?\]', '', text)
    text = re.sub(r'\(\d+\)', '', text)
    text = re.sub(r'[\*†]', '', text)
    text = re.sub(r'[¹²³⁴⁵⁶⁷⁸⁹]', '', text)
    text = re.sub(r'\b\d+\b', '', text)
    text = re.sub(r'<[^>]+>', '', text)
    text = re.sub(r'&[a-zA-Z0-9#]+;', ' ', text)
    text = re.sub(r'[^\w\s.,!?;ęóąśłżźćńĘÓĄŚŁŻŹĆŃ-]', '', text)
    return re.sub(r'\s+', ' ', text).strip()

def sanitize_filename(filename: str) -> str:
    """Oczyszcza nazwę pliku, zastępując niedozwolone znaki.
    
    Args:
        filename (str): Wejściowa nazwa pliku
        
    Returns:
        str: Oczyszczona nazwa pliku
    """
    return re.sub(r'[<>:"/\\|?*]', '_', filename).strip()

def detect_encoding(file_path: str) -> str:
    """Wykrywa kodowanie pliku za pomocą charset_normalizer.
    
    Args:
        file_path (str): Ścieżka do pliku
        
    Returns:
        str: Wykryte kodowanie
    """
    with open(file_path, "rb") as f:
        raw_data = f.read(10000)
    result = detect(raw_data)
    encoding = result["encoding"]
    logger.info(f"Wykryte kodowanie: {encoding}")
    return encoding

def extract_text_from_fb2(file_path: str, encoding: str) -> list:
    """Wyodrębnia rozdziały z pliku FB2.
    
    Args:
        file_path (str): Ścieżka do pliku FB2
        encoding (str): Kodowanie pliku
        
    Returns:
        list: Lista krotek (tytuł_rozdzialu, tekst_rozdzialu)
    """
    with open(file_path, "r", encoding=encoding) as f:
        content = f.read()
    root = ET.fromstring(content)
    chapters = []
    namespace = "{http://www.gribuser.ru/xml/fictionbook/2.0}"
    
    chapter_idx = 0
    for body in root.findall(f".//{namespace}body"):
        if body.get('name') == 'notes':
            continue
        for section in body.findall(f".//{namespace}section"):
            chapter_text = []
            title_elem = section.find(f"{namespace}title")
            title_p_elements = []
            if title_elem is not None:
                title_parts = []
                title_p_elements = title_elem.findall(f"{namespace}p")
                for p in title_p_elements:
                    if p.text:
                        title_parts.append(p.text.strip())
                chapter_title = " ".join(title_parts).strip() if title_parts else None
            else:
                chapter_title = None
            
            chapter_idx += 1
            if not chapter_title:
                chapter_title = f"Rozdział {chapter_idx}"
            
            for p in section.findall(f".//{namespace}p"):
                if p in title_p_elements:
                    continue
                if p.text:
                    if p.get('id', '').startswith('note') or p.get('type', '').lower() == 'footnote':
                        continue
                    chapter_text.append(p.text.strip())
            if chapter_text:
                chapters.append((chapter_title, " ".join(chapter_text)))
    return chapters

def extract_text_from_epub(file_path: str) -> list:
    """Wyodrębnia rozdziały z pliku EPUB.
    
    Args:
        file_path (str): Ścieżka do pliku EPUB
        
    Returns:
        list: Lista krotek (tytuł_rozdzialu, tekst_rozdzialu)
    """
    book = epub.read_epub(file_path)
    chapters = []
    current_chapter = []
    current_title = None
    chapter_idx = 0
    seen_titles = {}
    seen_content_hashes = set()
    metadata_patterns = [
        r'ISBN-+',
        r'Copyright',
        r'Utwór opracowany',
        r'wolnelektury\.pl',
        r'fundacj[aę] Wolne Lektury',
        r'Ta lektura, podobnie jak',
        r'Wszystkie zasoby Wolnych Lektur'
    ]
    
    main_title = book.get_metadata('DC', 'title')
    main_title = main_title[0][0] if main_title else "Nieznany Tytuł"
    logger.info(f"Główny tytuł EPUB: {main_title}")
    
    total_content_size = sum(len(item.get_content()) for item in book.get_items_of_type(ITEM_DOCUMENT))
    is_single_story = total_content_size < 500000
    logger.info(f"Rozmiar EPUB: {total_content_size} bajtów, traktowanie jako {'jedna historia' if is_single_story else 'książka wielorozdziałowa'}")
    
    for item in book.get_items_of_type(ITEM_DOCUMENT):
        content = item.get_content().decode("utf-8", errors="replace")
        soup = BeautifulSoup(content, "html.parser")
        
        if len(soup.text.strip()) < 100 or "toc" in item.get_name().lower() or "nav" in item.get_name().lower():
            logger.info(f"Pomijanie prawdopodobnego elementu TOC lub metadanych: {item.get_name()}")
            continue
            
        for element in soup.find_all(['h1', 'h2', 'h3', 'h4', 'p', 'div', 'span']):
            if ('footnote' in element.get('class', []) or 
                'note' in element.get('class', []) or 
                element.get('id', '').startswith('note') or
                'copyright' in element.get('class', []) or
                'metadata' in element.get('class', [])):
                continue
                
            if element.name in ['h1', 'h2', 'h3', 'h4']:
                if current_chapter:
                    chapter_content = " ".join(current_chapter)
                    
                    is_similar = False
                    if is_single_story and chapters:
                        for _, existing_content in chapters:
                            if text_similarity(chapter_content, existing_content) > 0.7:
                                logger.info(f"Treść wydaje się być podobna do istniejącego rozdziału, pomijanie")
                                is_similar = True
                                break
                    
                    content_hash = hash(chapter_content[:1000])
                    if not is_similar and content_hash not in seen_content_hashes and len(chapter_content) > 200:
                        seen_content_hashes.add(content_hash)
                        
                        use_title = main_title if is_single_story and len(chapter_content) > 5000 and "przypisy" not in current_title.lower() else current_title
                        
                        if use_title:
                            if use_title in seen_titles:
                                seen_titles[use_title] += 1
                                use_title = f"{use_title} ({seen_titles[use_title]})"
                            else:
                                seen_titles[use_title] = 1
                        else:
                            chapter_idx += 1
                            use_title = f"Rozdział {chapter_idx}"
                            
                        logger.info(f"Dodawanie rozdziału: {use_title} ({len(chapter_content)} znaków)")
                        chapters.append((use_title, chapter_content))
                    
                    current_chapter = []
                
                current_title = element.text.strip() if element.text else None
                if current_title and len(current_title) < 2:
                    current_title = None
                continue

            if element.name in ['p', 'div', 'span'] and element.text and element.text.strip():
                text = element.text.strip()
                
                is_metadata = any(re.search(pattern, text) for pattern in metadata_patterns)
                
                if len(text) > 10 and not is_metadata:
                    current_chapter.append(text)
    
    if current_chapter:
        chapter_content = " ".join(current_chapter)
        content_hash = hash(chapter_content[:1000])
        if content_hash not in seen_content_hashes and len(chapter_content) > 200:
            if current_title:
                if current_title in seen_titles:
                    seen_titles[current_title] += 1
                    current_title = f"{current_title} ({seen_titles[current_title]})"
                else:
                    seen_titles[current_title] = 1
            else:
                chapter_idx += 1
                current_title = f"Rozdział {chapter_idx}"
                
            logger.info(f"Dodawanie ostatniego rozdziału: {current_title} ({len(chapter_content)} znaków)")
            chapters.append((current_title, chapter_content))
    
    logger.info(f"Wyodrębniono {len(chapters)} unikalnych rozdziałów z EPUB")
    return chapters

def extract_metadata_from_fb2(file_path: str, encoding: str, filename: str = "") -> dict:
    """Wyodrębnia metadane z pliku FB2.
    
    Args:
        file_path (str): Ścieżka do pliku FB2
        encoding (str): Kodowanie pliku
        filename (str): Oryginalna nazwa pliku
        
    Returns:
        dict: Metadane z tytułem i autorem
    """
    try:
        with open(file_path, "r", encoding=encoding) as f:
            content = f.read()
        root = ET.fromstring(content)
        namespace = "{http://www.gribuser.ru/xml/fictionbook/2.0}"
        
        title_elem = root.find(f".//{namespace}book-title")
        title = title_elem.text.strip() if title_elem is not None and title_elem.text else filename.replace('.fb2', '')
        
        author_elem = root.find(f".//{namespace}author")
        author = "Nieznany"
        if author_elem is not None:
            first_name = author_elem.find(f"{namespace}first-name")
            last_name = author_elem.find(f"{namespace}last-name")
            author_parts = []
            if first_name is not None and first_name.text:
                author_parts.append(first_name.text.strip())
            if last_name is not None and last_name.text:
                author_parts.append(last_name.text.strip())
            author = " ".join(author_parts) if author_parts else "Nieznany"
        
        return {'title': title, 'author': author}
    except Exception as e:
        logger.error(f"Błąd podczas wyodrębniania metadanych FB2: {str(e)}")
        return {'title': filename.replace('.fb2', ''), 'author': "Nieznany"}

def extract_metadata_from_epub(file_path: str, filename: str = "") -> dict:
    """Wyodrębnia metadane z pliku EPUB.
    
    Args:
        file_path (str): Ścieżka do pliku EPUB
        filename (str): Oryginalna nazwa pliku
        
    Returns:
        dict: Metadane z tytułem i autorem
    """
    try:
        book = epub.read_epub(file_path)
        title = book.get_metadata('DC', 'title')
        title = title[0][0] if title else filename.replace('.epub', '')
        
        author = book.get_metadata('DC', 'creator')
        author = author[0][0] if author else "Nieznany"
        
        return {'title': title, 'author': author}
    except Exception as e:
        logger.error(f"Błąd podczas wyodrębniania metadanych EPUB: {str(e)}")
        return {'title': filename.replace('.epub', ''), 'author': "Nieznany"}

def split_text(text: str, max_length: int = 150) -> list:
    """Dzieli tekst na fragmenty odpowiednie do przetwarzania TTS.
    
    Args:
        text (str): Tekst wejściowy
        max_length (int): Maksymalna długość każdego fragmentu
        
    Returns:
        list: Lista fragmentów tekstu
    """
    chunks = []
    current_chunk = ""
    
    sentences = re.split(r'(?<=[.!?])\s+', text)
    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence or not re.search(r'[a-zA-ZęóąśłżźćńĘÓĄŚŁŻŹĆŃ]', sentence):
            continue
            
        while len(sentence) > max_length:
            split_point = sentence[:max_length].rfind(' ')
            if split_point == -1:
                split_point = max_length
            chunk = sentence[:split_point].strip()
            if re.search(r'[a-zA-ZęóąśłżźćńĘÓĄŚŁŻŹĆŃ]', chunk):
                chunks.append(chunk)
                logger.debug(f"Długość fragmentu: {len(chunk)}")
            sentence = sentence[split_point:].strip()
        
        if len(current_chunk) + len(sentence) <= max_length:
            if current_chunk:
                current_chunk += " " + sentence
            else:
                current_chunk = sentence
        else:
            if current_chunk:
                if re.search(r'[a-zA-ZęóąśłżźćńĘÓĄŚŁŻŹĆŃ]', current_chunk):
                    chunks.append(current_chunk.strip())
                    logger.debug(f"Długość fragmentu: {len(current_chunk.strip())}")
            current_chunk = sentence
    
    if current_chunk and re.search(r'[a-zA-ZęóąśłżźćńĘÓĄŚŁŻŹĆŃ]', current_chunk):
        chunks.append(current_chunk.strip())
        logger.debug(f"Długość fragmentu: {len(current_chunk.strip())}")
    
    return chunks

@app.route('/health', methods=['GET'])
def health_check():
    """Sprawdza status serwera.
    
    Returns:
        jsonify: Status serwera w formacie JSON
    """
    status = {
        'status': 'ok',
        'tts_model': tts.model_name,
        'device': device,
        'cuda_available': torch.cuda.is_available()
    }
    return jsonify(status)

@app.route('/upload', methods=['POST'])
def upload_file():
    """Przetwarza przesłany plik EPUB lub FB2, generując audiobook w formacie MP3.
    
    Returns:
        jsonify: Odpowiedź z plikiem ZIP w formacie base64 i metadanymi
    """
    start_time = time.time()
    logger.info(f"Żądanie od {request.remote_addr}")
    
    if 'file' not in request.files:
        logger.warning("Brak części pliku")
        return 'Brak części pliku', 400  
    file = request.files['file']
    if file.filename == '':
        logger.warning("Nie wybrano pliku")
        return 'Nie wybrano pliku', 400  
    if not (file.filename.endswith('.epub') or file.filename.endswith('.fb2')):
        logger.warning("Nieprawidłowy format pliku")
        return 'Plik musi być w formacie EPUB lub FB2', 400  

    temp_dir = tempfile.mkdtemp()
    try:
        input_path = os.path.join(temp_dir, file.filename)
        file.save(input_path)
        logger.info(f"Otrzymano plik: {file.filename}, rozmiar: {os.path.getsize(input_path)} bajtów")

        # Wyodrębnianie metadanych
        metadata = {}
        if file.filename.endswith(".fb2"):
            encoding = detect_encoding(input_path)
            chapters = extract_text_from_fb2(input_path, encoding)
            metadata = extract_metadata_from_fb2(input_path, encoding, file.filename)
        elif file.filename.endswith(".epub"):
            chapters = extract_text_from_epub(input_path)
            metadata = extract_metadata_from_epub(input_path, file.filename)
        else:
            raise ValueError("Format pliku musi być .fb2 lub .epub")  

        zip_path = os.path.join(temp_dir, f"chapters_{uuid.uuid4()}.zip")
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for chapter_idx, (chapter_title, chapter_text) in enumerate(chapters):
                logger.info(f"Przetwarzanie rozdziału {chapter_idx + 1}/{len(chapters)}: {chapter_title}")
                chapter_text = clean_text(chapter_text)
                if not chapter_text:
                    logger.info(f"Rozdział {chapter_idx + 1} jest pusty, pomijanie.")
                    continue
                
                text_chunks = split_text(chapter_text)
                logger.info(f"Rozdział {chapter_idx + 1} podzielony na {len(text_chunks)} fragmentów")
                
                temp_files = []
                failed_chunks = 0
                
                for chunk_idx, chunk in enumerate(text_chunks):
                    temp_wav = os.path.join(temp_dir, f"temp_{chapter_idx}_{chunk_idx}_{uuid.uuid4()}.wav")
                    logger.info(f"Syntezowanie fragmentu {chunk_idx+1}/{len(text_chunks)} dla rozdziału {chapter_idx + 1}")
                    try:
                        chunk = re.sub(r'ISBN-+', '', chunk)
                        chunk = re.sub(r'\s+', ' ', chunk).strip()
                        tts.tts_to_file(
                            text=chunk, 
                            file_path=temp_wav, 
                            speaker="Ana Florence", 
                            language="pl"
                        )
                        temp_files.append(temp_wav)
                    except Exception as e:
                        failed_chunks += 1
                        logger.error(f"Błąd podczas syntezowania fragmentu {chunk_idx+1}: {str(e)}")
                       
                        if failed_chunks < len(text_chunks) // 2:  
                            continue
                        else:
                            raise Exception(f"Zbyt wiele nieudanych fragmentów ({failed_chunks}/{len(text_chunks)}) w rozdziale {chapter_title}")
                
                if temp_files:
                    sanitized_title = sanitize_filename(chapter_title)
                    chapter_output_path = os.path.join(temp_dir, f"{sanitized_title}.mp3")
                    combined = AudioSegment.empty()
                    for temp_file in temp_files:
                        with open(temp_file, 'rb') as f:
                            audio = AudioSegment.from_wav(f)
                            combined += audio
                        os.remove(temp_file)
                    
                    combined.export(chapter_output_path, format="mp3", bitrate="192k")
                    zipf.write(chapter_output_path, f"{sanitized_title}.mp3")
                    combined = None
                    logger.info(f"Dodano do ZIP: {sanitized_title}.mp3")
                else:
                    logger.warning(f"Nie wygenerowano audio dla rozdziału {chapter_title}, pomijanie")

        # Odczyt pliku ZIP w formacie base64
        with open(zip_path, 'rb') as f:
            zip_data = base64.b64encode(f.read()).decode('utf-8')

        # Tworzenie odpowiedzi JSON
        response = {
            'zip_file': zip_data,
            'metadata': {
                'title': metadata.get('title', file.filename.replace('.epub', '').replace('.fb2', '')),
                'author': metadata.get('author', 'Nieznany')
            }
        }

        logger.info(f"Plik przetworzony w {time.time() - start_time:.2f} sekund")
        return jsonify(response)

    except Exception as e:
        logger.error(f"Błąd podczas przetwarzania pliku: {str(e)}")
        return f"Błąd serwera: {str(e)}", 500  
    finally:
        logger.info("Czyszczenie tymczasowych plików")
        try:
            shutil.rmtree(temp_dir, ignore_errors=True)
            logger.info(f"Usunięto katalog tymczasowy {temp_dir}")
        except Exception as e:
            logger.warning(f"Błąd podczas czyszczenia plików tymczasowych: {str(e)}")

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=False)