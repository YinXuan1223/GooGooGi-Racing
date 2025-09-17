from google import genai
from PIL import Image, ImageDraw
import os
from dotenv import load_dotenv
import speech_recognition as sr
import pyttsx3

load_dotenv()  

def main():

    client = genai.Client(api_key=os.getenv("GENAI_API_KEY"))

    model_name = "gemini-2.5-pro"

    image_path = "assets/test_line.jpg"  
    img = Image.open(image_path)
    #img.show()  # 顯示編輯後的圖片

    # 語音輸入
    r = sr.Recognizer()
    with sr.Microphone() as source:
        print("請說出您的問題...")
        audio = r.listen(source)

    try:
        # 辨識語音
        prompt = r.recognize_google(audio, language="zh-TW")
        print(f'user_question: {prompt}')

        # 使用 generate_content
        response = client.models.generate_content(
            model=model_name,
            contents=[prompt, img]
        )

        model_output = response.text
        print(f'model_output: {model_output}')

        # 語音輸出
        engine = pyttsx3.init()
        engine.say(model_output)
        engine.runAndWait()

    except sr.UnknownValueError:
        print("語音無法辨識")
    except sr.RequestError as e:
        print("無法從 Google 語音辨識服務取得結果; {0}".format(e))

    '''prompt = "我現在不知道怎麼回到有許多聯絡人的畫面，怎麼辦。"

    # 使用 generate_content
    response = client.models.generate_content(
        model=model_name,
        contents=[prompt, img]
    )

    # 印出結果
    print(f'user_question: {prompt}')
    print(f'model_output: {response.text}')'''


if __name__ == "__main__":
    main()
