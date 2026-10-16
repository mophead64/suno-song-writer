# Song Writer

An Android app that runs your AI songwriting team for Suno, so you don't have to copy and paste between five chat apps to get a song.

## What it does

If you write Suno songs by bouncing ideas between Claude, ChatGPT, Gemini, Grok and DeepSeek, getting lyrics from one, sharing everyone's style ideas, and refining until they agree, Song Writer does that juggling for you on one screen.

You set up a **team**: each member is an AI model with a name and a role, like "Gemini, lyricist" or "Claude, reviewer". Then, for each song:

- **Start from an idea.** Type a line or two and Quick generate drafts the lyrics, a style prompt and the Suno sliders, or write the first version yourself.
- **Let the team work.** In the **Writers' Room**, the team writes, proposes styles and reviews the song in rounds. Each round, the suggestions are accepted or rejected, and the accepted ones are applied to make a new version. You chair it and decide what goes in, or let it run on its own.
- **Get two versions, as you do now.** The agreed version is saved as *Consensus*. A second copy is then refined against the Suno v6 prompting guide (the "foundation framework" step) as *Framework-refined*.
- **Send it to Suno.** Suno itself stays in your hands. A handoff screen gives you separate Copy buttons for the style and the lyrics, checks them against the guide's rules (missing ending, no instrumental buffer before the last line, and so on), and lists the sliders to set. It also keeps an exact copy of what you sent.
- **Learn from what came back.** Paste the Suno links, rate the takes and tag problems like "lyrics cut off" or "distant vocals". *Fix from results* turns those notes into a better next version, and songs that come out well can be saved as **recipes** to reuse.

Every change is kept as a version, so you can see exactly what changed between drafts and go back to any of them. Songs live in a library with folders, artists, genre tags and search.

## What it costs

It's free to use with free models, and free-only mode is on from the start. Your team can be:

- models running on your own computer through Ollama
- free API keys from Gemini, Groq or OpenRouter
- the free plans of the chat apps you already use, where the app writes each prompt for you to paste across and reads the reply when you paste it back.

API keys stay on your phone. If you do use paid models, the app tracks the cost and can stop at a monthly limit.

## Getting started

Install the APK, open the app and follow **Getting started**. It walks you through connecting your first model, building a team and writing your first song.
