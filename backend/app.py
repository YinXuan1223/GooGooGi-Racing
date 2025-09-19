import os
from flask import Flask, request, jsonify
from datetime import datetime

# 初始化 Flask 應用
app = Flask(__name__)

# 設定上傳資料夾的路徑
UPLOAD_FOLDER = 'uploads'
# 確保上傳資料夾存在
if not os.path.exists(UPLOAD_FOLDER):
    os.makedirs(UPLOAD_FOLDER)

app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

@app.route('/')
def index():
    return "<h1>測試用的圖片上傳後端</h1><p>請將 POST 請求發送到 /upload</p>"

# 建立一個 API 端點來接收圖片上傳
@app.route('/upload', methods=['POST'])
def upload_image():
    # 檢查請求中是否包含檔案部分
    if 'screenshot_file' not in request.files:
        print("錯誤：請求中沒有找到名為 'screenshot_file' 的檔案部分")
        return jsonify({"error": "請求中缺少檔案部分"}), 400

    file = request.files['screenshot_file']

    # 如果使用者沒有選擇檔案，瀏覽器可能會提交一個沒有檔名的空部分
    if file.filename == '':
        print("錯誤：沒有選擇檔案")
        return jsonify({"error": "沒有選擇檔案"}), 400

    if file:
        # 產生一個安全且唯一的時間戳檔名
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        filename = f"screenshot_{timestamp}.png"

        # 完整的儲存路徑
        save_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)

        try:
            # 將檔案儲存到指定路徑
            file.save(save_path)
            print(f"成功：圖片已儲存至 {save_path}")
            # 回傳成功訊息
            return jsonify({"message": "圖片上傳成功", "filename": filename}), 200
        except Exception as e:
            print(f"錯誤：儲存檔案時發生問題 - {e}")
            return jsonify({"error": f"儲存檔案時發生錯誤: {e}"}), 500

if __name__ == '__main__':
    # 監聽所有網路介面，這樣模擬器或實體手機才能連線
    # port=5000 是預設的埠號
    app.run(host='0.0.0.0', port=5000, debug=True)
