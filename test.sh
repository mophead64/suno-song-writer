OPENROUTER_API_KEY="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"

curl https://openrouter.ai/api/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $OPENROUTER_API_KEY" \
  -d '{
  "model": "~openai/gpt-sol-latest",
  "messages": [
    {
      "role": "user",
      "content": "What is the meaning of life?"
    }
  ]
}'