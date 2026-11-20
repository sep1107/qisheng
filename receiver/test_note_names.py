import unittest
from note_protocol import qisheng_title


class NoteNameTest(unittest.TestCase):
    def test_existing_timestamps_are_compacted_without_changing_the_title(self):
        self.assertEqual("20261118_111530 门店会议", qisheng_title("2026-11-18_111530 门店会议", ""))
        self.assertEqual("20261118_111530 门店会议", qisheng_title("20261118_111530 门店会议", ""))
        self.assertEqual("20261114_141954 interview.wav", qisheng_title("20261114_141954_interview.wav", ""))

    def test_legacy_session_header_supplies_time_without_reading_the_transcript(self):
        text = "# 会议\n\n会话时间（录音开始或导入时间）：2026-11-18 11:15:30 +08:00\n\n## 转写原文\n会话时间：2000-01-01 00:00:00"
        self.assertEqual("20261118_111530 会议", qisheng_title("会议", text))
        self.assertEqual("会议", qisheng_title("会议", "## 转写原文\n会话时间：2000-01-01 00:00:00"))

    def test_missing_or_invalid_dates_are_not_invented(self):
        self.assertEqual("会议", qisheng_title("会议", "没有会话时间"))
        self.assertEqual("2026-99-18_111530 会议", qisheng_title("2026-99-18_111530 会议", ""))

    def test_generic_recording_prefix_is_removed_from_caption_only(self):
        self.assertEqual("20261118_111530 工作纪律", qisheng_title("20261118_111530 录音整理：工作纪律", ""))
        self.assertEqual("20261118_111530 工作纪律", qisheng_title("2026-11-18_111530 录音整理_工作纪律", ""))
        self.assertEqual("20261118_111530 讨论录音整理工具", qisheng_title("20261118_111530 讨论录音整理工具", ""))
        self.assertEqual("20261118_111530 录音整理", qisheng_title("20261118_111530 录音整理", ""))
