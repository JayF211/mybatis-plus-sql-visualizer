# -*- coding: utf-8 -*-
"""组件定位协议回归：不依赖正在运行的 IDE。"""
import unittest
from unittest.mock import patch

import robot


class RobotProtocolTest(unittest.TestCase):
    def test_find_components_uses_plural_endpoint_and_status(self):
        response = {"status": "SUCCESS", "elementList": [{"id": "1", "width": 50, "height": 20}]}
        with patch.object(robot, "_request", return_value=response) as request:
            self.assertEqual([{"id": "1", "width": 50, "height": 20}],
                             robot.find_components("//div"))
        self.assertEqual("/xpath/components", request.call_args.args[0])
        self.assertEqual({"xpath": "//div"}, request.call_args.args[1])

    def test_server_error_and_ambiguous_match_never_click(self):
        with patch.object(robot, "_request", return_value={"status": "ERROR", "message": "bad xpath"}):
            with self.assertRaisesRegex(robot.RobotServerError, "bad xpath"):
                robot.find_components("//div")
        two = [{"id": "1", "width": 50, "height": 20},
               {"id": "2", "width": 50, "height": 20}]
        with patch.object(robot, "find_components", return_value=two):
            with self.assertRaisesRegex(robot.RobotServerError, "2 个可见组件"):
                robot.find_one("//div")

    def test_component_screen_center_sums_ancestor_offsets(self):
        comp = {"id": "tab", "className": "TabLabel", "x": 12, "y": 2,
                "width": 80, "height": 30}
        chain = [{"className": "com.intellij.openapi.wm.impl.IdeFrameImpl", "x": 260, "y": 20},
                 {"className": "IdeRootPane", "x": 8, "y": 0},
                 {"className": "Panel", "x": 852, "y": 40}]
        with patch.object(robot, "find_components", return_value=chain):
            self.assertEqual((1172, 77), robot.component_screen_center("//div", comp))
        with patch.object(robot, "find_components", return_value=chain[1:]):
            with self.assertRaisesRegex(robot.RobotServerError, "完整祖先链"):
                robot.component_screen_center("//div", comp)
        with self.assertRaises(robot.RobotServerError):
            robot.component_center({"id": "hidden", "x": 0, "y": 0, "width": 0, "height": 40})

    def test_screenshot_decodes_signed_byte_array(self):
        png = b"\x89PNG\r\n\x1a\n" + b"payload"
        with patch.object(robot, "_request", return_value={
            "status": "SUCCESS", "bytes": [b if b < 128 else b - 256 for b in png]
        }):
            self.assertEqual(png, robot.screenshot())
        with patch.object(robot, "_request", return_value={"status": "ERROR", "message": "denied"}):
            with self.assertRaisesRegex(robot.RobotServerError, "denied"):
                robot.screenshot()


if __name__ == "__main__":
    unittest.main()
