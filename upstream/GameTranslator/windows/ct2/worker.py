# SPDX-License-Identifier: AGPL-3.0-only
"""GameTranslator'ın yerel çevirisi için kalıcı CTranslate2 süreci; her satırda bir JSON nesnesi.

CouchSubs'ın windows/ct2/worker.py dosyasından uyarlandı.
Argümanlar: <model klasörü> <cpu|cuda> <işlemci iş parçacığı>
Girdi:  {"tokens": [...], "beams": 4, "max_output": 64}
Çıktı:  {"ready": true} (açılışta bir kez), sonra her istek için {"tokens": [...]} ya da {"error": "..."}
"""

import json
import os
import sys

# Uygulama Python'u yalıtılmış (-I) başlatır; o kipte PYTHONIOENCODING okunmaz. Belirteçlerde "▁" gibi karakterler
# var: giriş ve çıkış açıkça UTF-8 olmalı (yoksa Windows'un eski kod sayfası "charmap" hatası veriyordu).
sys.stdin.reconfigure(encoding="utf-8")
sys.stdout.reconfigure(encoding="utf-8")

# Ekran kartı: NVIDIA'nın CUDA kütüphaneleri (cuBLAS, cudart) uygulamanın indirdiği klasörden yüklenir.
# Klasör argüman olarak gelir (yalıtılmış kipte ortam değişkeni okunmaz).
cuda_dir = sys.argv[4] if len(sys.argv) > 4 else ""
if cuda_dir:
    _cuda_handle = os.add_dll_directory(cuda_dir)
    os.environ["PATH"] = cuda_dir + os.pathsep + os.environ.get("PATH", "")
    import ctypes
    ctypes.windll.kernel32.SetDllDirectoryW(cuda_dir)


def reply(value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


try:
    import ctranslate2

    device = sys.argv[2]
    translator = ctranslate2.Translator(
        sys.argv[1],
        device=device,
        compute_type="float16" if device == "cuda" else "int8",
        inter_threads=1,
        intra_threads=int(sys.argv[3]),
    )
    # Model yüklense de CUDA bağımlılığı ilk çıkarımda hata verebilir: burada denenir
    translator.translate_batch([["▁Hello", ".", "</s>"]], beam_size=1)
    reply({"ready": True})
except Exception as error:
    reply({"ready": False, "error": str(error)})
    sys.exit(1)

# Uygulama kapanınca stdin kapanır, döngü biter ve süreç kendiliğinden çıkar
for line in sys.stdin:
    try:
        request = json.loads(line)
        result = translator.translate_batch(
            [request["tokens"]],
            beam_size=request["beams"],
            max_decoding_length=request["max_output"],
        )
        reply({"tokens": result[0].hypotheses[0]})
    except Exception as error:
        reply({"error": str(error)})
