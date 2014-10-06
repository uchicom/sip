/**
 * (c) 2014 uchicom
 */
package com.uchicom.dirsip;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;


/**
 * 出力時に一度全てバッファに溜め込むので負荷があがってしまう。
 * 随時書き込むようにしないといけない。
 * @author uchicom: Shigeki Uchiyama
 *
 */
public class SipHandler implements Handler {

    /** 出力用の文字列バッファ */
//    StringBuffer strBuff = new StringBuffer(1024 * 2);
    /** ダイジェスト用の変数 */
    String timestamp;

    // ユーザーコマンドでユーザーが設定されたかどうかのフラグ
    /** ユーザー設定済みフラグ */
    boolean bUser;
    // 認証が許可されたかどうかのフラグ
    /** 認証済みフラグ */
    boolean bPass;
    /** 終了フラグ */
    boolean finished;
    long startTime = System.currentTimeMillis();
    
    /** パスワード */
    String pass;
    /** ベースディレクトリ */
    File base;
    /** ユーザーメールボックス */
    File userBox;
    // メールbox内にあるメールリスト(PASSコマンド時に認証が許可されると設定される)
    /** メールボックス内のリスト */
    List<File> mailList;
    // DELEコマンド時に指定したメールが格納される(PASSコマンド時に認証が許可されると設定される)
    /** 削除リスト */
    List<File> delList;
    ByteBuffer readBuff = ByteBuffer.allocate(1024);
    ByteArrayOutputStream baos = new ByteArrayOutputStream(); 
    String invite;
    SelectionKey inviteKey;
    LinkedBlockingQueue<StringBuffer> queue = new LinkedBlockingQueue<StringBuffer>();
    

    StringBuilder recieve = new StringBuilder();
    
    private Map<String, SelectionKey> registMap = new HashMap<String, SelectionKey>();
    public SipHandler(Map<String, SelectionKey> registMap) {
    	this.registMap = registMap;
    }
    /* (non-Javadoc)
     * @see com.uchicom.dirpop3.Handler#handle(java.nio.channels.SelectionKey)
     */
    @Override
    public void handle(SelectionKey key) throws IOException, NoSuchAlgorithmException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (key.isReadable()) {
            int length = channel.read(readBuff);
            if (length > 0) {
                check(new String(Arrays.copyOfRange(readBuff.array(), 0, readBuff.position())), key);
                readBuff.clear();
        		if (queue.size() > 0) {
        			key.interestOps(SelectionKey.OP_WRITE);
        		}
            }

        }
        if (key.isWritable() && queue.size() > 0) {
        	StringBuffer strBuff = queue.poll();
            //初回の出力を実施
            int size = channel.write(ByteBuffer.wrap(strBuff.toString().getBytes()));
        	System.out.println("送信====>");
        	System.out.println(strBuff.toString());
        	System.out.println("<====");
        	if (invite != null) {
        		if (inviteKey.attachment() != null) {
        		try {
					((SipHandler)inviteKey.attachment()).queue.put(new StringBuffer(invite));
	        		inviteKey.interestOps(SelectionKey.OP_WRITE);
	        		invite = null;
	        		inviteKey = null;
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
        		}
        	}
            //書き込み処理が終わっていないかを確認する。
            //処理が途中の場合は途中から実施する。
            if (size < strBuff.length()) {
            	strBuff.delete(0, size);
            } else {
            	queue.poll();
	            key.interestOps(SelectionKey.OP_READ);
	            //終了処理
	            if (finished) {
	            	key.cancel();
	            	channel.close();
	            }
            }
        }
    }
    /**
     * コマンド行が入力されたかどうかチェックする.
     * @return
     */
    public void check(String data, SelectionKey key) {

    	System.out.println("受信---->");
    	System.out.println(data);
    	System.out.println("<----");

//		System.out.println("____kaiseki____>");
        kaiseki(data, key);
//		System.out.println("<____kaiseki____");
    }
    public void kaiseki(String data, SelectionKey key) {
    	recieve.append(data);
    	StringBuffer strBuff = null;
    	while (recieve.length() > 0) {
    		String value = recieve.toString();
//    		System.out.println("@@@@@@@@@@@@@@@@@@@");
//    		System.out.println(value);
//    		System.out.println("@@@@@@@@@@@@@@@@@@@");
    		int sepIndex = value.indexOf("\r\n\r\n");
    		if (sepIndex < 0) {
    			//もっと待つ
    			break;
    		}
    		String startLineHeader = value.substring(0, sepIndex + 2);
    		System.out.println("startLineHeader:" + startLineHeader);
    		int contentLengthStartIndex = startLineHeader.indexOf("Content-Length");
    		if (contentLengthStartIndex < 0) {
    			break;
    		}
    		System.out.println("contentLengthStartIndex:" + contentLengthStartIndex);
    		int contentLengthEndIndex = startLineHeader.indexOf("\r\n", contentLengthStartIndex);
    		if (contentLengthEndIndex < 0) {
    			break;
    		}
    		System.out.println("contentLengthEndIndex:" + contentLengthEndIndex);
    		int contentLength = Integer.parseInt(startLineHeader.substring(contentLengthStartIndex, contentLengthEndIndex).split(": *")[1]);
    		System.out.println(contentLength);
    		if (value.length() < sepIndex + contentLength) {
    			//もっと待つ
    			break;
    		}

    		strBuff = new StringBuffer(sepIndex + 4 + contentLength);
	    	String[] lines = startLineHeader.split("\r\n");
	    	String[] methods = lines[0].split(" ");
	    	String method = methods[0];
			int mode = 0;
	    	if ("REGISTER".equals(method)) {
		        strBuff.append("SIP/2.0 200 OK\r\n");
	            mode = 1;
	    	} else if ("INVITE".equals(method)) {
	            strBuff.append("SIP/2.0 100 Tring\r\n");
	            mode =2;
	    	} else if ("CANCEL".equals(method)) {
		        strBuff.append("SIP/2.0 200 OK\r\n");
	            mode =3;
	    	} else if ("SIP/2.0".equals(method)) {
	    		if ("100".equals(methods[1])) {
		    		mode = 4;
	    		} else if ("180".equals(methods[1])) {
	    			mode = 5;
	    		} else if ("200".equals(methods[1])) {
	    			mode = 6;
	    		}
	    	}
			int bodyLength = -1;
			String fromUser = null;
			boolean authrization = false;
	    	for (int i = 1; i < lines.length; i++) {
	    		String line = lines[i];
	
	            if ("".equals(line)) {
					if (bodyLength <= 0) {
						break;
					} else {
						break;
					}
	            }
				if (line.startsWith("Max-Forwards") ||
						line.startsWith("Contact") ||
						line.startsWith("Content-Type")) {
					//返信に付加しない
				} else if (line.startsWith("Content-Length:")) {
					if (mode == 1 && !authrization) {
						strBuff.replace(8, 14, "401 Unauthorized");
						strBuff.append("WWW-Authenticate: Digest realm=\"hoge.com\", qop\"auth\", nonce=\"aaa\", opaque=\"\", stale=FALSE, algorithm=MD5\r\n");
					}
					bodyLength = Integer.parseInt(line.split(" +")[1]);
				    strBuff.append("Content-Length: 0\r\n");
				} else if (line.startsWith("Authorization:")) {
					authrization = true;
				} else {
					strBuff.append(line);
					if (line.startsWith("To")) {
						strBuff.append(";tag=" + System.currentTimeMillis());
						String toUser = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
						if ((mode == 2 || mode == 3) && registMap.containsKey(toUser)) {
							invite = value.substring(0, sepIndex + 4 + contentLength);
							inviteKey = registMap.get(toUser);
							System.out.println("INVITE!!" );
						}
					} else if (line.startsWith("From")) {
						fromUser = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
						if (mode == 1) {
							registMap.put(fromUser, key);
						} else if (mode == 5 && registMap.containsKey(fromUser)) {
							invite = value.substring(0, sepIndex + 4 + contentLength);
							inviteKey = registMap.get(fromUser);
							System.out.println("RINGING!!" );
						}
					} else if (line.startsWith("CSeq")) {
						if (mode == 6 && "INVITE".equals(line.split(" ")[2])) {
							invite = value.substring(0, sepIndex + 4 + contentLength);
							inviteKey = registMap.get(fromUser);
							System.out.println("CONNECT!!" );
						}
					}
					strBuff.append("\r\n");
				}
	    	}
			//メッセージボディが無くても空行が必要
	        strBuff.append("\r\n");
			recieve.delete(0, sepIndex + 4 + contentLength);
			try {
				queue.put(strBuff);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				break;
			}
    	}
    }
    
}