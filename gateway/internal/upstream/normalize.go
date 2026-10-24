// normalize.go 把第三方客户端的非标准 part 归一化成 Chat Completions 形态。
// WorkBuddy 的 /v2/chat/completions 只认 {"type":"image_url","image_url":{"url":"..."}}，
// Responses API 风格的 input_image、字符串形式的 image_url 都会被忽略，
// 表现为模型回复“没有收到图片”。
package upstream

import (
	"encoding/json"
	"strings"
)

// NormalizeImageParts 扫描 messages[].content[]，把图片 part 统一成 image_url。
// 返回修改后的 body 与改动数量；无需修改时原样返回。
func NormalizeImageParts(body []byte) ([]byte, int, error) {
	if len(body) == 0 {
		return body, 0, nil
	}
	var root map[string]any
	if err := json.Unmarshal(body, &root); err != nil {
		return body, 0, nil
	}
	msgs, ok := root["messages"].([]any)
	if !ok {
		return body, 0, nil
	}
	changed := 0
	for _, m := range msgs {
		msg, ok := m.(map[string]any)
		if !ok {
			continue
		}
		parts, ok := msg["content"].([]any)
		if !ok {
			continue
		}
		for i, p := range parts {
			part, ok := p.(map[string]any)
			if !ok {
				continue
			}
			typ, _ := part["type"].(string)
			switch strings.ToLower(typ) {
			case "input_image", "image":
				url, detail := imageURLOf(part, typ)
				if url == "" {
					continue
				}
				obj := map[string]any{"url": url}
				if detail != "" {
					obj["detail"] = detail
				}
				parts[i] = map[string]any{"type": "image_url", "image_url": obj}
				changed++
			case "image_url":
				switch v := part["image_url"].(type) {
				case string:
					if v == "" {
						continue
					}
					part["image_url"] = map[string]any{"url": v}
					changed++
				case map[string]any:
					if _, has := v["url"]; !has {
						if s, _ := v["image_url"].(string); s != "" {
							v["url"] = s
							changed++
						}
					}
				}
			}
		}
	}
	if changed == 0 {
		return body, 0, nil
	}
	out, err := json.Marshal(root)
	if err != nil {
		return body, 0, err
	}
	return out, changed, nil
}

// imageURLOf 从 input_image / image part 里取出 URL，兼容字符串与对象两种写法。
func imageURLOf(part map[string]any, typ string) (url, detail string) {
	if v, ok := part[typ]; ok {
		switch t := v.(type) {
		case string:
			url = t
		case map[string]any:
			url, _ = t["url"].(string)
			if url == "" {
				url, _ = t["image_url"].(string)
			}
			detail, _ = t["detail"].(string)
		}
	}
	if url == "" {
		if s, ok := part["image_url"].(string); ok {
			url = s
		} else if o, ok := part["image_url"].(map[string]any); ok {
			url, _ = o["url"].(string)
			detail, _ = o["detail"].(string)
		}
	}
	if url == "" {
		url, _ = part["url"].(string)
	}
	if detail == "" {
		detail, _ = part["detail"].(string)
	}
	return url, detail
}
