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


@app.route("/ui", methods=["POST"])
def ui_server():
    try:
        # 接收錄音檔
        file = request.files.get("file")
        if not file:
            return jsonify({"error": "未找到語音檔案"}), 400
        
        # 接收 json 資料
        ui_data_str = request.form.get("ui_data")
        if not ui_data_str:
            return jsonify({"error": "未找到 UI 資料"}), 400
        ui_data = json.loads(ui_data_str)
        
        # 暫存音檔
        input_path = "temp_input"
        wav_path = "temp.wav"
        file.save(input_path)

        # 將語音轉為 WAV 格式 (16kHz, mono) 以供辨識
        sound = AudioSegment.from_file(input_path)
        sound = sound.set_frame_rate(16000).set_channels(1)
        sound.export(wav_path, format="wav")

        # 語音轉文字 (使用 Google 語音辨識服務)
        r = sr.Recognizer()
        with sr.AudioFile(wav_path) as source:
            audio = r.record(source)
            text = r.recognize_google(audio, language="zh-TW")


        # load ui structure example
        with open("assets/UI_Structure.json", "r") as f:
            UI_structure = json.loads(f)
        
        user_prompt = {
            
            "任務":f'''
            你是一個熟悉 Android 系統智慧型手機操作流程的專家，
            請分析使用者的問題(通常是他想做到某件事但不知道怎麼做)，透過 UI 結構觀察現在手機的畫面，大概長這樣({UI_structure})，
            回覆一個 JSON 格式:
            - 如果手機畫面已經達成使用者想做到的事，請回復 {"mission_achieved": True, "ai_response": "恭喜你，達成任務", "selected_ui_element": None}
            - 如果手機畫面尚未達成使用者想做到的事，告訴使用者下一步該怎麼做，並用 json 格式回傳操作的 UI element，回復 {"mission_achieved": False, "ai_response": "你建議的下一步", "selected_ui_element":"a json representation for select ui element"}
            ''',
            "使用者的問題": text,
            "現在手機得到的 UI 結構": ui_data
        }

        
        
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=json.dumps(user_prompt),
            config=types.GenerateContentConfig(
                system_instruction="You are given an android UI structure and an user question. Please help me analyze the question.",
                response_mime_type="application/json",
                response_schema={
                    "type": "OBJECT",
                    "properties": {
                        "mission_achieved": {"type": "BOOL"},
                        "ai_response": {"type": "STRING"},
                        "selected_ui_element": {
                            "type": "OBJECT",
                            "properties": {

                                "className": {"type": "STRING"},
                                "text": {"type": "STRING"},
                                "contentDescription": {"type": "STRING"},
                                "viewId": {"type": "STRING"},
                                "bounds": {
                                    "type": "OBJECT",
                                    "properties": {
                                        "l": {"type":int},
                                        "t": {"type":int},
                                        "r": {"type":int},
                                        "b": {"type":int}
                                    }
                                }
                                
                            }
                        },
                    },
                    "required": ["mission_achieved", "ai_response", "selected_ui_element"]
                }
                
            ),
        )


        model_output_json_str = response.text
        model_output = json.loads(model_output_json_str)
        mission_achieved = model_output.get("mission_achieved", False)
        ai_response = model_output.get("ai_response", "抱歉，我無法理解您的意思。")
        selected_ui_element = model_output.get("selected_ui_element", {})
                
        
        
        # 將引導文字轉為語音 (使用 gTTS)
        tts = gTTS(ai_response, lang="zh-TW")
        out_file_path = tempfile.NamedTemporaryFile(delete=False, suffix=".mp3")
        tts.save(out_file_path)
        
        # 讀取音檔內容並轉換為 base64 編碼
        with open(out_file_path, "rb") as audio_file:
            encoded_audio = base64.b64encode(audio_file.read()).decode('utf-8')
            
        # 回傳所有資料給前端
        return jsonify({
            "mission_achieved": mission_achieved,
            "ai_response": ai_response,
            "audio_base64": encoded_audio,
            "selected_ui_element": selected_ui_element
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