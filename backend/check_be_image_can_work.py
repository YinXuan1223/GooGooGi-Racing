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


image1 = Image.open("assets/test_line.jpg")
image2 = Image.open("assets/line_dinner_test.jpg")


# 語音轉 WAV 格式 (16kHz, mono)
wav_path = "temp.wav"
sound = AudioSegment.from_file("assets/dinner_input.wav")
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
請分析使用者的問題(通常是他想做到某件事但不知道怎麼做)，觀察現在手機的畫面，回覆一個 JSON 格式:
- 如果手機畫面已經達成使用者想做到的事，請回復 {"mission_achieved": true, "ai_response": "恭喜你，達成任務"}
- 如果手機畫面尚未達成使用者想做到的事，幫使用者規劃下一步該怎麼做(點擊哪個框框，輸入什麼東西等等，盡量不要超過25個中文字)，
回復 {"mission_achieved": false, "ai_response": "你建議使用者下一步該怎麼做"}
""",
"使用者的問題": text
}

# Gemini 輸出強制為 JSON
response = client.models.generate_content(
    model="gemini-2.5-flash",
    contents=[image2, json.dumps(user_prompt)],
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

# 建立輸出文件夾（如果不存在的話）
output_dir = "audio_output"
os.makedirs(output_dir, exist_ok=True)

# 使用時間戳記建立文件名
import datetime
timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
output_filename = f"ai_response_{timestamp}.mp3"
output_path = os.path.join(output_dir, output_filename)

# 保存語音文件
tts.save(output_path)
print(f"語音文件已保存至: {output_path}")

# 語音轉 Base64
with open(output_path, "rb") as audio_file:
    encoded_audio = base64.b64encode(audio_file.read()).decode("utf-8")

print(f"mission_achieved:{mission_achieved}, ai_response:{ai_response}")


