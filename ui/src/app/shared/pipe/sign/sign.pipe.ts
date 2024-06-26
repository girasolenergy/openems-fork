import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
    name: 'sign',
})
export class SignPipe implements PipeTransform {
    transform(value:any, args: string[]): any {
        const positive = value * -1;
        return positive;
    }
}
