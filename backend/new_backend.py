from flask import Flask, request, jsonify

import os
import json
from dotenv import load_dotenv

import base64
import tempfile
from gtts import gTTS
from pydub import AudioSegment
import speech_recognition as sr

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
            return jsonify({"error": "未找到語音檔案"}), 400

        # 接收圖片
        image_file = request.files.get("image")
        if not image_file:
            return jsonify({"error": "未找到圖片"}), 400
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
        out_file_path = tempfile.NamedTemporaryFile(delete=False, suffix=".mp3")
        tts.save(out_file_path.name)

        # 語音轉 Base64
        with open(out_file_path.name, "rb") as audio_file:
            encoded_audio = base64.b64encode(audio_file.read()).decode("utf-8")

        return jsonify({
            "mission_achieved": mission_achieved,
            "ai_response": ai_response,
            "audio_base64": encoded_audio
        })

    except sr.UnknownValueError:
        return jsonify({"error": "Google 語音辨識服務無法辨識語音"}), 500
    except sr.RequestError as e:
        return jsonify({"error": f"無法從 Google 語音辨識服務取得結果; {e}"}), 500
    except Exception as e:
        return jsonify({"error": f"處理請求時發生錯誤: {str(e)}"}), 500
    finally:
        for path in [locals().get("input_path"), locals().get("wav_path"), locals().get("out_file_path", None) and locals()["out_file_path"].name]:
            if path and os.path.exists(path):
                os.remove(path)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)