## 前置準備
- Git： 確保電腦有安裝 Git。
- 虛擬環境： 推薦使用 Conda 來管理專案的 Python 環境。


## 環境建置
- 把 repo clone 下來 :
    ```
    git clone https://github.com/YinXuan1223/GooGooGi-Racing.git
    ```
- 進到專案資料夾，用 Conda 建立一個新的虛擬環境(名字叫 GooGooGi， 但也可以自己取)：
    ```
    cd GooGooGi-Racing
    conda create -n GooGooGi python=3.10

    ```
- 開啟虛擬環境 (執行完後你的terminal prompt 前面會出現(GooGooGi))
    ```
    conda activate GooGooGi
    ```
- 如果想要關掉虛擬環境的話，執行以下指令((執行完後你的terminal prompt 前面會出現(base)))
    ```
    conda deactivate
    ```

- 安裝 python 需要的套件
    ```
    pip install -r requirements.txt
    ```

- 設定環境變數
為了安全起見，我們將 API 金鑰儲存在環境變數中，而不是直接寫在程式碼裡。
請在專案根目錄下建立一個 .env 檔案，並填入 google API key (在 line 群組)。
    ```
    # .env 檔案內容
    GEMINI_API_KEY="API key"
    ```