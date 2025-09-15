from google import genai
import os
from dotenv import load_dotenv
load_dotenv()

def list_models():
    client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"))

    models = client.models.list()
    for m in models:
        print("模型名稱:", m.name)
        # print("支援方法:", m.supported_generation_methods)
        # print("描述:", getattr(m, "description", ""))
        print("-" * 50)

if __name__ == "__main__":
    list_models()
