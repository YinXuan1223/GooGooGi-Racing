# server code for debugging
from flask import Flask, request, jsonify

import os
import json
from dotenv import load_dotenv
from datetime import datetime

import base64
import tempfile
from gtts import gTTS
from pydub import AudioSegment
import speech_recognition as sr

from PIL import Image

from google import genai
from google.genai import types

from PIL import Image

from google import genai
from google.genai import types

load_dotenv()
app = Flask(__name__)

client = genai.Client(api_key=os.getenv("GENAI_API_KEY"))

@app.route("/img", methods=["POST"])
def img_server():
    try:
        # 接收錄音檔
        file = request.files.get("file")
        if not file:
            print("no voice")
            return jsonify({"error": "未找到語音檔案"}), 400
        else:
            print("voice received")

        # 接收圖片
        image_file = request.files.get("image")
        if not image_file:
            print("no image")
            return jsonify({"error": "未找到圖片"}), 400
        else :
            print("image received")

        # 建立 assets 目錄（如果不存在）
        assets_dir = "assets"
        if not os.path.exists(assets_dir):
            os.makedirs(assets_dir)

        # 生成帶時間戳的檔名
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        original_filename = image_file.filename or "image"
        file_extension = os.path.splitext(original_filename)[1] or ".jpg"
        saved_image_filename = f"received_image_{timestamp}{file_extension}"
        saved_image_path = os.path.join(assets_dir, saved_image_filename)

        # 儲存圖片
        image_file.seek(0)  # 重置文件指針到開頭
        image_file.save(saved_image_path)
        print(f"圖片已儲存至: {saved_image_path}")

        # 開啟圖片進行處理
        image_file.seek(0)  # 重置文件指針以重新讀取
        image = Image.open(image_file.stream)
        
        # 保存原始語音檔案
        original_voice_filename = file.filename or "voice"
        voice_file_extension = os.path.splitext(original_voice_filename)[1] or ".mp3"
        saved_voice_filename = f"received_voice_{timestamp}{voice_file_extension}"
        saved_voice_path = os.path.join(assets_dir, saved_voice_filename)
        
        # 儲存原始語音檔案
        file.seek(0)  # 重置文件指針到開頭
        file.save(saved_voice_path)
        print(f"語音檔案已儲存至: {saved_voice_path}")

        # 暫存音檔進行處理
        input_path = "temp_input"
        wav_path = "temp.wav"
        file.seek(0)  # 重置文件指針
        file.save(input_path)

        # 語音轉 WAV 格式 (16kHz, mono)
        sound = AudioSegment.from_file(input_path)
        sound = sound.set_frame_rate(16000).set_channels(1)
        sound.export(wav_path, format="wav")
        
        # 同時保存處理後的 WAV 檔案
        saved_wav_filename = f"processed_voice_{timestamp}.wav"
        saved_wav_path = os.path.join(assets_dir, saved_wav_filename)
        sound.export(saved_wav_path, format="wav")
        print(f"處理後的WAV檔案已儲存至: {saved_wav_path}")

        # 語音轉文字
        r = sr.Recognizer()
        with sr.AudioFile(wav_path) as source:
            audio = r.record(source)
            text = r.recognize_google(audio, language="zh-TW")

        print(f"text: {text}")

        # Prompt
        user_prompt = {
            "任務": """
            你是一個熟悉 Android 系統智慧型手機操作流程的專家，
            請分析使用者的問題(通常是他想做到某件事但不知道怎麼做)，觀察現在手機的畫面，
            回覆一個 JSON 格式:
            - 如果手機畫面已經達成使用者想做到的事，請回復 {"mission_achieved": true, "ai_response": "恭喜你，達成任務"}
            - 如果手機畫面尚未達成使用者想做到的事，幫使用者規劃下一步該怎麼做(點擊哪個框框，輸入什麼東西等等)，回復 {"mission_achieved": false, "ai_response": "你建議的下一步"}
            """,
            "使用者的問題": text
        }

        # Gemini 輸出強制為 JSON
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=[image, json.dumps(user_prompt)],
            config=types.GenerateContentConfig(
                system_instruction="你是操作 Android 系統智慧型手機的專家",
                response_mime_type="application/json",
                response_schema={
                    "type": "object",
                    "properties": {
                        "mission_achieved": {"type": "boolean"},
                        "ai_response": {"type": "string"}
                    },
                    "required": ["mission_achieved", "ai_response"]
                }
            )
        )

        # 確保 AI 回傳 JSON
        ai_json = json.loads(response.text)
        mission_achieved = ai_json.get("mission_achieved", False)
        ai_response = ai_json.get("ai_response", "")

        # 文字轉語音
        tts = gTTS(ai_response, lang="zh-TW")
        
        # Use context manager for better file handling
        with tempfile.NamedTemporaryFile(delete=False, suffix=".mp3") as out_file_path:
            temp_audio_path = out_file_path.name
        
        # Save TTS to the temporary file
        tts.save(temp_audio_path)

        # 語音轉 Base64
        with open(temp_audio_path, "rb") as audio_file:
            encoded_audio = base64.b64encode(audio_file.read()).decode("utf-8")

        print(f"mission achieved? {mission_achieved}, ai response: {ai_response}")
        return jsonify({
            "mission_achieved": mission_achieved,
            "ai_response": ai_response,
            "audio_base64": encoded_audio,
        })

    except sr.UnknownValueError:
        return jsonify({"error": "Google 語音辨識服務無法辨識語音"}), 500
    except sr.RequestError as e:
        return jsonify({"error": f"無法從 Google 語音辨識服務取得結果; {e}"}), 500
    except Exception as e:
        return jsonify({"error": f"處理請求時發生錯誤: {str(e)}"}), 500
    finally:
        # Clean up temporary files
        cleanup_files = []
        
        # Add files to cleanup list if they exist in locals
        if 'input_path' in locals():
            cleanup_files.append(locals()['input_path'])
        if 'wav_path' in locals():
            cleanup_files.append(locals()['wav_path'])
        if 'temp_audio_path' in locals():
            cleanup_files.append(locals()['temp_audio_path'])
        
        # Note: saved_image_path, saved_voice_path, and saved_wav_path are kept permanently in assets directory
        # If you want to clean up saved files as well, uncomment the next lines:
        # if 'saved_image_path' in locals():
        #     cleanup_files.append(locals()['saved_image_path'])
        # if 'saved_voice_path' in locals():
        #     cleanup_files.append(locals()['saved_voice_path'])
        # if 'saved_wav_path' in locals():
        #     cleanup_files.append(locals()['saved_wav_path'])
        
        # Clean up each file with error handling
        for path in cleanup_files:
            if path and os.path.exists(path):
                try:
                    os.remove(path)
                except PermissionError:
                    # File might still be in use, try to delete later
                    print(f"Warning: Could not delete {path} - file may be in use")
                except Exception as e:
                    print(f"Warning: Error deleting {path}: {e}")

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)