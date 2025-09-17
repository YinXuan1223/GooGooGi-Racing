from google import genai
from PIL import Image
import os
from dotenv import load_dotenv

load_dotenv()  

def main():


    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise ValueError("請先設定環境變數 GEMINI_API_KEY")

    client = genai.Client(api_key=api_key)

    
    model_name = "gemini-2.5-flash"

    image_path = "assets/test_line.jpg"  
    img = Image.open(image_path)

    prompt = "我現在不知道怎麼回到有許多聯絡人的畫面，怎麼辦。"

    # 使用 generate_content
    response = client.models.generate_content(
        model=model_name,
        contents=[prompt, img]
    )

    # 印出結果
    print(f'user_question: {prompt}')
    print(f'model_output: {response.text}')

if __name__ == "__main__":
    main()
