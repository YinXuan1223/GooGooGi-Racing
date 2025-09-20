from flask import Flask, request, jsonify

from flask import Flask, request, jsonify

import os
import json
import json
from dotenv import load_dotenv

import base64
import tempfile
from gtts import gTTS
from pydub import AudioSegment

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
load_dotenv()
app = Flask(__name__)

client = genai.Client(api_key=os.getenv("GENAI_API_KEY"))
client = genai.Client(api_key=os.getenv("GENAI_API_KEY"))

@app.route("/img", methods=["POST"])
def img_server():
    try:
        # 接收錄音檔
        file = request.files.get("file")
        if not file:
            return jsonify({"error": "未找到語音檔案"}), 400

        # 接收圖片
        image_file = request.files.get("image")
        if not image_file:
            print("no image")
            return jsonify({"error": "未找到圖片"}), 400
        else :
            print("image received")
        image = Image.open(image_file.stream)

        # 暫存音檔
        input_path = "temp_input"
        wav_path = "temp.wav"
        file.save(input_path)

        # 語音轉 WAV 格式 (16kHz, mono)
        sound = AudioSegment.from_file(input_path)
        sound = sound.set_frame_rate(16000).set_channels(1)
        sound.export(wav_path, format="wav")

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
            "audio_base64": encoded_audio
        })

    except sr.UnknownValueError:
        return jsonify({"error": "Google 語音辨識服務無法辨識語音"}), 500
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
    app.run(host="0.0.0.0", port=5000, debug=True)