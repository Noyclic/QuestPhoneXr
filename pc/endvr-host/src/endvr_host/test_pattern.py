from io import BytesIO
from PIL import Image, ImageDraw

def make_test_frame(width: int, height: int, frame_number: int) -> bytes:
    if width < 160 or height < 120:
        raise ValueError("frame dimensions must be at least 160x120")
    image = Image.new("RGB", (width, height), (12, 18, 28))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, width//2, height), fill=(24, 52, 76))
    draw.rectangle((width//2, 0, width, height), fill=(76, 38, 42))
    draw.line((width//2, 0, width//2, height), fill=(245,245,245), width=2)
    radius = max(8, min(width,height)//18)
    x = radius + (frame_number*7) % max(1, width-2*radius)
    y = height//2
    draw.ellipse((x-radius,y-radius,x+radius,y+radius), fill=(255,210,64), outline=(255,255,255), width=3)
    draw.text((18,16), "EndVR EV-0 TEST PATTERN", fill=(255,255,255))
    draw.text((18,38), f"frame {frame_number}", fill=(210,225,240))
    draw.text((18,height-28), "LEFT EYE / BLUE", fill=(220,235,255))
    draw.text((width//2+18,height-28), "RIGHT EYE / RED", fill=(255,225,225))
    output = BytesIO()
    image.save(output, format="JPEG", quality=80)
    return output.getvalue()
