#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <mutex>

#include "whisper.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "whisper_jni", __VA_ARGS__)

namespace {
    static std::mutex g_ctx_mutex;
    static std::string g_loaded_model_path;
    static whisper_context * g_ctx = nullptr;

    static std::string jstringToStd(JNIEnv *env, jstring value) {
        if (!value) return "";
        const char *chars = env->GetStringUTFChars(value, nullptr);
        std::string out = chars ? chars : "";
        if (chars) env->ReleaseStringUTFChars(value, chars);
        return out;
    }

    static std::string jsonEscape(const std::string &input) {
        std::ostringstream out;
        for (char c : input) {
            switch (c) {
                case '\\': out << "\\\\"; break;
                case '"': out << "\\\""; break;
                case '\n': out << "\\n"; break;
                case '\r': out << "\\r"; break;
                case '\t': out << "\\t"; break;
                default: out << c; break;
            }
        }
        return out.str();
    }

    static bool loadPcm16WavMono16k(const std::string &path, std::vector<float> &pcmf32) {
        std::ifstream in(path, std::ios::binary);
        if (!in) return false;

        char riff[4];
        in.read(riff, 4);
        if (std::strncmp(riff, "RIFF", 4) != 0) return false;
        in.ignore(4);
        char wave[4];
        in.read(wave, 4);
        if (std::strncmp(wave, "WAVE", 4) != 0) return false;

        uint16_t audioFormat = 0;
        uint16_t channels = 0;
        uint32_t sampleRate = 0;
        uint16_t bitsPerSample = 0;
        std::vector<int16_t> pcm16;

        while (in && !in.eof()) {
            char chunkId[4] = {0};
            uint32_t chunkSize = 0;
            in.read(chunkId, 4);
            if (!in) break;
            in.read(reinterpret_cast<char *>(&chunkSize), sizeof(chunkSize));
            if (!in) break;

            if (std::strncmp(chunkId, "fmt ", 4) == 0) {
                if (chunkSize < 16) return false;
                uint32_t byteRate = 0;
                uint16_t blockAlign = 0;
                in.read(reinterpret_cast<char *>(&audioFormat), sizeof(audioFormat));
                in.read(reinterpret_cast<char *>(&channels), sizeof(channels));
                in.read(reinterpret_cast<char *>(&sampleRate), sizeof(sampleRate));
                in.read(reinterpret_cast<char *>(&byteRate), sizeof(byteRate));
                in.read(reinterpret_cast<char *>(&blockAlign), sizeof(blockAlign));
                in.read(reinterpret_cast<char *>(&bitsPerSample), sizeof(bitsPerSample));
                if (chunkSize > 16) {
                    in.ignore(chunkSize - 16);
                }
            } else if (std::strncmp(chunkId, "data", 4) == 0) {
                if (chunkSize == 0) continue;
                pcm16.resize(chunkSize / sizeof(int16_t));
                in.read(reinterpret_cast<char *>(pcm16.data()), static_cast<std::streamsize>(chunkSize));
            } else {
                in.ignore(chunkSize);
            }
        }

        if (audioFormat != 1 || channels != 1 || sampleRate != 16000 || bitsPerSample != 16 || pcm16.empty()) {
            return false;
        }
        pcmf32.resize(pcm16.size());
        for (size_t i = 0; i < pcm16.size(); ++i) {
            pcmf32[i] = static_cast<float>(pcm16[i]) / 32768.0f;
        }
        return true;
    }

    static whisper_context * ensureContext(const std::string &modelPath) {
        std::lock_guard<std::mutex> lock(g_ctx_mutex);
        if (g_ctx != nullptr && g_loaded_model_path == modelPath) {
            return g_ctx;
        }
        if (g_ctx != nullptr) {
            whisper_free(g_ctx);
            g_ctx = nullptr;
            g_loaded_model_path.clear();
        }
        whisper_context_params cparams = whisper_context_default_params();
        g_ctx = whisper_init_from_file_with_params(modelPath.c_str(), cparams);
        if (g_ctx != nullptr) {
            g_loaded_model_path = modelPath;
        }
        return g_ctx;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_project3_WhisperJniBridge_transcribeVerboseJson(
        JNIEnv *env,
        jobject /* this */,
        jstring modelPathJ,
        jstring audioPathJ,
        jstring languageJ,
        jboolean enableWordTimestamps,
        jint nThreads) {
    const std::string modelPath = jstringToStd(env, modelPathJ);
    const std::string audioPath = jstringToStd(env, audioPathJ);
    const std::string language = jstringToStd(env, languageJ);

    struct whisper_context *ctx = ensureContext(modelPath);
    if (!ctx) {
        std::string err = "{\"error\":\"failed to load whisper model\"}";
        return env->NewStringUTF(err.c_str());
    }

    std::vector<float> pcmf32;
    if (!loadPcm16WavMono16k(audioPath, pcmf32)) {
        std::string err = "{\"error\":\"failed to load wav audio. expected mono 16k pcm16 wav\"}";
        return env->NewStringUTF(err.c_str());
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.token_timestamps = enableWordTimestamps == JNI_TRUE;
    params.max_len = 0;
    params.max_tokens = 0;
    params.n_threads = std::max(1, static_cast<int>(nThreads));
    params.language = language.empty() ? "en" : language.c_str();

    if (whisper_full(ctx, params, pcmf32.data(), static_cast<int>(pcmf32.size())) != 0) {
        std::string err = "{\"error\":\"whisper inference failed\"}";
        return env->NewStringUTF(err.c_str());
    }

    const int nSegments = whisper_full_n_segments(ctx);
    std::ostringstream json;
    json << "{\"text\":\"\",\"language\":\"" << jsonEscape(language.empty() ? "en" : language)
         << "\",\"duration\":" << (static_cast<double>(pcmf32.size()) / 16000.0)
         << ",\"segments\":[";

    std::ostringstream fullText;
    bool firstSeg = true;
    bool firstWordGlobal = true;
    std::ostringstream wordsJson;
    const bool includeWords = enableWordTimestamps == JNI_TRUE;
    if (includeWords) {
        wordsJson << "[";
    }

    for (int i = 0; i < nSegments; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i);
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
        const char *segTextRaw = whisper_full_get_segment_text(ctx, i);
        std::string segText = segTextRaw ? segTextRaw : "";
        fullText << segText;

        if (!firstSeg) json << ",";
        firstSeg = false;
        json << "{\"id\":" << i
             << ",\"start\":" << (t0 * 0.01)
             << ",\"end\":" << (t1 * 0.01)
             << ",\"text\":\"" << jsonEscape(segText) << "\"";

        if (includeWords) {
            json << ",\"words\":[";
            const int nTok = whisper_full_n_tokens(ctx, i);
            bool firstWordInSeg = true;
            for (int j = 0; j < nTok; ++j) {
                whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
                const char *tokTextRaw = whisper_full_get_token_text(ctx, i, j);
                std::string tokText = tokTextRaw ? tokTextRaw : "";
                if (tokText.empty()) continue;

                // Skip special and timestamp-like tokens.
                if (!tokText.empty() && tokText.front() == '[') continue;
                if (tokText.find("<|") != std::string::npos) continue;

                const double ws = td.t0 * 0.01;
                const double we = td.t1 * 0.01;
                if (we < ws) continue;

                if (!firstWordInSeg) json << ",";
                firstWordInSeg = false;
                json << "{\"word\":\"" << jsonEscape(tokText)
                    << "\",\"start\":" << ws
                    << ",\"end\":" << we << "}";

                if (!firstWordGlobal) wordsJson << ",";
                firstWordGlobal = false;
                wordsJson << "{\"word\":\"" << jsonEscape(tokText)
                        << "\",\"start\":" << ws
                        << ",\"end\":" << we << "}";
            }
            json << "]";
        }
        json << "}";
    }

    json << "]";
    if (includeWords) {
        wordsJson << "]";
        json << ",\"words\":" << wordsJson.str();
    }
    json << ",\"text\":\"" << jsonEscape(fullText.str()) << "\"}";

    const std::string out = json.str();
    return env->NewStringUTF(out.c_str());
}
